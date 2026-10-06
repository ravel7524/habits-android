#include <jni.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>
#include <sys/stat.h>

#ifndef HABITS_LLAMA_UNSUPPORTED
#include "llama.h"
#endif

namespace {
constexpr const char * kVersion = "llama.cpp/5f436dddb440a288ee5611d7d1eca564a6aca9f4;cpu;liquid;ctx=1536/6144;sample=ios-1337";
class Failure : public std::exception {
public:
    explicit Failure(const char * text) : text_(text) {}
    const char * what() const noexcept override { return text_; }
private:
    const char * text_;
};

void fail_java(JNIEnv * env, const char * message) {
    if (!env->ExceptionCheck()) {
        jclass type = env->FindClass("java/lang/IllegalStateException");
        if (type) env->ThrowNew(type, message);
    }
}

#ifndef HABITS_LLAMA_UNSUPPORTED
constexpr int kFastContext = 1536;
constexpr int kReasoningContext = 6144;
constexpr int kBatch = 128;
constexpr size_t kMaxTextBytes = 65536;
constexpr size_t kMaxOutputBytes = 4096;
constexpr size_t kMaxReasoningBytes = 65536;
constexpr const char * kThinkingEnd = "</think>";
using Clock = std::chrono::steady_clock;
int64_t now_ns() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now().time_since_epoch()).count();
}
struct ModelFree { void operator()(llama_model * p) const { if (p) llama_model_free(p); } };
struct ContextFree { void operator()(llama_context * p) const { if (p) llama_free(p); } };
struct SamplerFree { void operator()(llama_sampler * p) const { if (p) llama_sampler_free(p); } };
using Model = std::unique_ptr<llama_model, ModelFree>;
using Context = std::unique_ptr<llama_context, ContextFree>;
using Sampler = std::unique_ptr<llama_sampler, SamplerFree>;

void add_sampler(llama_sampler * chain, Sampler sampler) {
    if (!sampler) throw Failure("Local inference memory is unavailable.");
    llama_sampler_chain_add(chain, sampler.get());
    sampler.release();
}

struct State {
    Model model;
    bool load_started = false; // Protected by work_mutex, as is model access.
    std::atomic<bool> cancelled{false};
    std::atomic<bool> released{false};
    std::atomic<int64_t> deadline{0};
};
std::mutex registry_mutex;
std::mutex work_mutex;
std::unordered_map<jlong, std::shared_ptr<State>> states;
jlong next_handle = 1;
std::once_flag initialized;

void quiet_log(enum ggml_log_level, const char *, void *) {}
void initialize() {
    std::call_once(initialized, [] {
        llama_log_set(quiet_log, nullptr);
        ggml_log_set(quiet_log, nullptr);
        llama_backend_init();
    });
}
bool abort_work(void * data) {
    auto * state = static_cast<State *>(data);
    return state->cancelled.load(std::memory_order_relaxed) ||
           state->released.load(std::memory_order_relaxed) || now_ns() >= state->deadline.load();
}
bool load_progress(float, void * data) { return !abort_work(data); }
void check_active(State & state) {
    if (state.cancelled.load() || state.released.load()) throw Failure("Local inference was cancelled.");
    if (now_ns() >= state.deadline.load()) throw Failure("Local inference timed out.");
}
std::unique_lock<std::mutex> exclusive_work() {
    std::unique_lock<std::mutex> lock(work_mutex, std::try_to_lock);
    if (!lock.owns_lock()) throw Failure("Local inference is busy.");
    return lock;
}
std::shared_ptr<State> find_state(jlong handle) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto found = states.find(handle);
    if (found == states.end()) throw Failure("Local model is unavailable.");
    return found->second;
}
std::string read_bytes(JNIEnv * env, jbyteArray array, size_t limit) {
    if (!array) throw Failure("Invalid local inference request.");
    jsize length = env->GetArrayLength(array);
    if (length < 0 || static_cast<size_t>(length) > limit) throw Failure("Local inference input is too large.");
    std::string text(static_cast<size_t>(length), '\0');
    if (length) env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte *>(text.data()));
    if (env->ExceptionCheck()) throw Failure("Invalid local inference request.");
    if (text.find('\0') != std::string::npos) throw Failure("Invalid local inference request.");
    return text;
}
uint64_t available_memory() {
    std::unique_ptr<FILE, decltype(&fclose)> file(fopen("/proc/meminfo", "r"), fclose);
    if (!file) return 0;
    char line[256];
    while (fgets(line, sizeof(line), file.get())) {
        unsigned long long kb = 0;
        if (sscanf(line, "MemAvailable: %llu kB", &kb) == 1) return kb * 1024ULL;
    }
    return 0;
}
void check_model_file(const std::string & path) {
    struct stat info{};
    if (path.empty() || stat(path.c_str(), &info) != 0 || !S_ISREG(info.st_mode) ||
        info.st_size < 16 || info.st_size > 2LL * 1024 * 1024 * 1024) {
        throw Failure("Local model file is invalid.");
    }
    std::unique_ptr<FILE, decltype(&fclose)> file(fopen(path.c_str(), "rb"), fclose);
    char magic[4]{};
    if (!file || fread(magic, 1, sizeof(magic), file.get()) != sizeof(magic) || memcmp(magic, "GGUF", 4) != 0) {
        throw Failure("Local model file is invalid.");
    }
    auto available = available_memory();
    if (!available || available < static_cast<uint64_t>(info.st_size) + 256ULL * 1024 * 1024) {
        throw Failure("Local inference memory is unavailable.");
    }
}
std::string trim(const std::string & text) {
    auto first = text.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return {};
    return text.substr(first, text.find_last_not_of(" \t\r\n") - first + 1);
}
std::string format_chat(llama_model * model, const std::string & system, const std::string & user, bool reasoning) {
    char architecture[32]{};
    int length = llama_model_meta_val_str(model, "general.architecture", architecture, sizeof(architecture));
    if (length < 0 || length >= static_cast<int>(sizeof(architecture)) || strcmp(architecture, "lfm2") != 0) {
        throw Failure("Local model chat format is unsupported.");
    }
    // Match the pinned iPhone Liquid prompt. Never include a literal BOS: the loaded vocabulary supplies it once.
    auto normalized_system = trim(system);
    auto normalized_user = trim(user);
    std::string prompt;
    if (!normalized_system.empty()) prompt = "<|im_start|>system\n" + normalized_system + "<|im_end|>\n";
    prompt += "<|im_start|>user\n" + normalized_user + "<|im_end|>\n<|im_start|>assistant\n";
    if (reasoning) prompt += "<think>";
    if (prompt.size() > kMaxTextBytes) throw Failure("Local inference input is too large.");
    return prompt;
}
Sampler make_constraint(const llama_vocab * vocab, const std::string & grammar, bool reasoning) {
    if (!reasoning) return Sampler(llama_sampler_init_grammar(vocab, grammar.c_str(), "root"));
    llama_token trigger[16];
    int count = llama_tokenize(vocab, kThinkingEnd, static_cast<int>(strlen(kThinkingEnd)), trigger, 16, false, true);
    if (count == 1) {
        // Lazy token triggers include the trigger itself. Consume the closing marker before entering the caller's JSON root.
        if (grammar.find("habits-final-root") != std::string::npos) throw Failure("Local inference grammar is invalid.");
        std::string delayed = "habits-final-root ::= \"</think>\" root\n" + grammar;
        return Sampler(llama_sampler_init_grammar_lazy_patterns(vocab, delayed.c_str(), "habits-final-root", nullptr, 0, trigger, 1));
    }
    // Preserve correctness if a compatible tokenizer splits the closing marker across token pieces.
    // A nonempty capture prevents upstream's empty-group fallback from replaying hidden reasoning.
    const char * pattern = "^[\\s\\S]*?</think>[ \\t\\r\\n]*([^ \\t\\r\\n][\\s\\S]*)";
    return Sampler(llama_sampler_init_grammar_lazy_patterns(vocab, grammar.c_str(), "root", &pattern, 1, nullptr, 0));
}
std::string generate(State & state, const std::string & system, const std::string & user,
                     const std::string & grammar, int max_tokens, bool reasoning) {
    state.deadline.store(now_ns() + 90LL * 1000 * 1000 * 1000);
    check_active(state);
    if (!state.model) throw Failure("Local model is unavailable.");
    if (max_tokens < 1 || max_tokens > (reasoning ? 2048 : 256) || grammar.empty() || grammar.size() > 16384) {
        throw Failure("Invalid local inference request.");
    }
    auto prompt = format_chat(state.model.get(), system, user, reasoning);
    auto * vocab = llama_model_get_vocab(state.model.get());
    int context_size = reasoning ? kReasoningContext : kFastContext;
    std::vector<llama_token> tokens(context_size);
    int count = llama_tokenize(vocab, prompt.data(), static_cast<int>(prompt.size()), tokens.data(), context_size, false, true);
    // Keep the complete source facts and template. Never truncate JSON or fact identifiers to fit.
    if (count <= 0 || count + 1 + max_tokens >= context_size) throw Failure("Local inference input is too large.");
    tokens.resize(static_cast<size_t>(count));
    auto bos = llama_vocab_bos(vocab);
    if (bos == LLAMA_TOKEN_NULL || std::find(tokens.begin(), tokens.end(), bos) != tokens.end()) {
        throw Failure("Local inference input is invalid.");
    }
    tokens.insert(tokens.begin(), bos);
    ++count;
    check_active(state);
    auto sampler_params = llama_sampler_chain_default_params();
    sampler_params.no_perf = true;
    Sampler sampler(llama_sampler_chain_init(sampler_params));
    // Parse and validate the delayed/final JSON grammar before any model decode.
    Sampler constraint = make_constraint(vocab, grammar, reasoning);
    if (!sampler || !constraint) throw Failure("Local inference grammar is invalid.");
    add_sampler(sampler.get(), std::move(constraint));
    // Match the iPhone Liquid profiles. Grammar masks precede top-k so every final choice remains valid.
    add_sampler(sampler.get(), Sampler(llama_sampler_init_top_k(50)));
    add_sampler(sampler.get(), Sampler(llama_sampler_init_top_p(1.0f, 1)));
    add_sampler(sampler.get(), Sampler(llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64,
                reasoning ? 1.10f : 1.05f, 0.0f, 0.0f)));
    add_sampler(sampler.get(), Sampler(llama_sampler_init_temp(0.10f)));
    add_sampler(sampler.get(), Sampler(llama_sampler_init_dist(1337)));
    auto params = llama_context_default_params();
    params.n_ctx = context_size;
    params.n_batch = kBatch;
    params.n_ubatch = kBatch;
    params.n_seq_max = 1;
    params.n_threads = static_cast<int>(std::clamp(std::thread::hardware_concurrency(), 1U, 4U));
    params.n_threads_batch = params.n_threads;
    params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    params.offload_kqv = false;
    params.op_offload = false;
    params.no_perf = true;
    params.abort_callback = abort_work;
    params.abort_callback_data = &state;
    Context context(llama_init_from_model(state.model.get(), params));
    if (!context) throw Failure("Local inference memory is unavailable.");
    for (int offset = 0; offset < count; offset += kBatch) {
        check_active(state);
        auto batch = llama_batch_get_one(tokens.data() + offset, std::min(kBatch, count - offset));
        if (llama_decode(context.get(), batch) != 0) {
            check_active(state);
            throw Failure("Local inference failed.");
        }
    }
    std::string output;
    std::string marker_tail;
    size_t generated_bytes = 0;
    bool reasoning_done = !reasoning;
    bool finished = false;
    for (int index = 0; index < max_tokens; ++index) {
        check_active(state);
        if (!llama_get_logits_ith(context.get(), -1)) throw Failure("Local inference failed.");
        llama_token token = llama_sampler_sample(sampler.get(), context.get(), -1);
        if (llama_vocab_is_eog(vocab, token)) { finished = true; break; }
        std::vector<char> piece(256);
        int length = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, reasoning);
        if (length < 0 && length > -static_cast<int>(kMaxOutputBytes)) {
            piece.resize(static_cast<size_t>(-length));
            length = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, reasoning);
        }
        if (length < 0 || static_cast<size_t>(length) > piece.size()) {
            throw Failure("Local inference output is too large.");
        }
        generated_bytes += static_cast<size_t>(length);
        if (generated_bytes > kMaxReasoningBytes) throw Failure("Local inference output is too large.");
        if (!reasoning_done) {
            // Retain only a marker-sized tail. Never build, return or log a reasoning transcript.
            marker_tail.append(piece.data(), static_cast<size_t>(length));
            auto end = marker_tail.find(kThinkingEnd);
            if (end != std::string::npos) {
                output = marker_tail.substr(end + strlen(kThinkingEnd));
                marker_tail.clear();
                reasoning_done = true;
            } else if (marker_tail.size() >= strlen(kThinkingEnd)) {
                marker_tail.erase(0, marker_tail.size() - (strlen(kThinkingEnd) - 1));
            }
        } else {
            output.append(piece.data(), static_cast<size_t>(length));
        }
        if (output.size() > kMaxOutputBytes) throw Failure("Local inference output is too large.");
        if (llama_decode(context.get(), llama_batch_get_one(&token, 1)) != 0) {
            check_active(state);
            throw Failure("Local inference failed.");
        }
    }
    check_active(state);
    if (!finished || !reasoning_done || trim(output).empty()) throw Failure("Local inference output is incomplete.");
    return output;
}
#endif
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_ch_habits_android_localai_NativeLlama_nativeSupported(JNIEnv *, jobject) {
#ifdef HABITS_LLAMA_UNSUPPORTED
    return JNI_FALSE;
#else
    return JNI_TRUE;
#endif
}
extern "C" JNIEXPORT jstring JNICALL
Java_ch_habits_android_localai_NativeLlama_nativeVersion(JNIEnv * env, jobject) {
    return env->NewStringUTF(kVersion);
}
extern "C" JNIEXPORT jlong JNICALL
Java_ch_habits_android_localai_NativeLlama_nativeStartLoad(JNIEnv * env, jobject) {
#ifdef HABITS_LLAMA_UNSUPPORTED
    fail_java(env, "Local inference is unavailable.");
#else
    try {
        auto work = exclusive_work();
        auto state = std::make_shared<State>();
        std::lock_guard<std::mutex> lock(registry_mutex);
        if (!states.empty()) throw Failure("A local model is already reserved.");
        if (next_handle == std::numeric_limits<jlong>::max()) throw Failure("Local inference is unavailable.");
        jlong handle = next_handle++;
        states.emplace(handle, state);
        return handle;
    } catch (const Failure & error) { fail_java(env, error.what()); }
      catch (const std::bad_alloc &) { fail_java(env, "Local inference memory is unavailable."); }
      catch (...) { fail_java(env, "Local model could not be reserved."); }
#endif
    return 0;
}
extern "C" JNIEXPORT void JNICALL
Java_ch_habits_android_localai_NativeLlama_nativeFinishLoad(JNIEnv * env, jobject, jlong handle, jbyteArray path) {
#ifdef HABITS_LLAMA_UNSUPPORTED
    (void) handle; (void) path;
    fail_java(env, "Local inference is unavailable.");
#else
    try {
        auto work = exclusive_work();
        auto state = find_state(handle);
        state->deadline.store(now_ns() + 90LL * 1000 * 1000 * 1000);
        check_active(*state);
        if (state->load_started || state->model) throw Failure("Local model load is unavailable.");
        state->load_started = true;
        initialize();
        auto text = read_bytes(env, path, 16384);
        check_model_file(text);
        check_active(*state);
        auto params = llama_model_default_params();
        params.n_gpu_layers = 0;
        params.load_mode = LLAMA_LOAD_MODE_MMAP;
        params.use_extra_bufts = false;
        params.progress_callback = load_progress;
        params.progress_callback_user_data = state.get();
        // Keep both state and the tentative model local while the registry owner may cancel/release.
        Model model(llama_model_load_from_file(text.c_str(), params));
        check_active(*state);
        if (!model) throw Failure("Local model could not be loaded.");
        state->model = std::move(model);
        check_active(*state);
    } catch (const Failure & error) { fail_java(env, error.what()); }
      catch (const std::bad_alloc &) { fail_java(env, "Local inference memory is unavailable."); }
      catch (...) { fail_java(env, "Local model could not be loaded."); }
#endif
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_ch_habits_android_localai_NativeLlama_nativeGenerate(JNIEnv * env, jobject, jlong handle,
        jbyteArray system, jbyteArray user, jbyteArray grammar, jint max_tokens, jboolean reasoning) {
#ifdef HABITS_LLAMA_UNSUPPORTED
    (void) handle; (void) system; (void) user; (void) grammar; (void) max_tokens; (void) reasoning;
    fail_java(env, "Local inference is unavailable.");
#else
    try {
        auto work = exclusive_work();
        auto state = find_state(handle);
        auto output = generate(*state, read_bytes(env, system, kMaxTextBytes), read_bytes(env, user, kMaxTextBytes),
                               read_bytes(env, grammar, 16384), max_tokens, reasoning == JNI_TRUE);
        auto array = env->NewByteArray(static_cast<jsize>(output.size()));
        if (array) env->SetByteArrayRegion(array, 0, static_cast<jsize>(output.size()), reinterpret_cast<const jbyte *>(output.data()));
        return array;
    } catch (const Failure & error) { fail_java(env, error.what()); }
      catch (const std::bad_alloc &) { fail_java(env, "Local inference memory is unavailable."); }
      catch (...) { fail_java(env, "Local inference failed."); }
#endif
    return nullptr;
}
extern "C" JNIEXPORT void JNICALL
Java_ch_habits_android_localai_NativeLlama_nativeCancel(JNIEnv *, jobject, jlong handle) {
#ifdef HABITS_LLAMA_UNSUPPORTED
    (void) handle;
#else
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto found = states.find(handle);
    if (found != states.end()) found->second->cancelled.store(true);
#endif
}
extern "C" JNIEXPORT void JNICALL
Java_ch_habits_android_localai_NativeLlama_nativeRelease(JNIEnv *, jobject, jlong handle) {
#ifdef HABITS_LLAMA_UNSUPPORTED
    (void) handle;
#else
    std::shared_ptr<State> state;
    {
        std::lock_guard<std::mutex> lock(registry_mutex);
        auto found = states.find(handle);
        if (found == states.end()) return;
        state = found->second;
        state->released.store(true);
        state->cancelled.store(true);
        states.erase(found);
    }
    // A concurrent load/generation retains shared ownership until its local native resources are destroyed.
#endif
}
