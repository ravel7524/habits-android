# Optional on-device report highlights

The 0.4 preview includes downloaded Liquid models and CPU JNI inference. The public 0.4 package carries the 0.2 device integrations and 0.3 model work, with full original-source highlighting in the reworked UI. See [current verification](verification/0.4.0/README.md); the dated 0.3 checkpoints below remain historical. Physical-phone acceptance is open.

## User-controlled lifecycle

Settings → On-device AI separates preset selection, downloading, verification, loading into memory, unloading and removing the selected stored model. Download and load each require an explicit action; choosing another preset does not start either. Startup may verify an existing file on IO; it never starts a download or loads native model memory. A verified file is not a loaded model.

ARM64 and x86_64 use the CPU-only llama.cpp bridge. The 32-bit ARM build contains an unsupported stub, retaining basic planning and deterministic reports. Runtime gating excludes low-RAM devices, requires at least 2 GiB of total memory for fast or 5 GiB for balanced, and checks available memory before loading. These checks reduce predictable failures; they are not a guarantee against Android killing a process under later memory pressure.

Model operations run off the UI thread. Foreground loss cancels loading/generation, signals the registered native operation and releases its handle. Explicit Cancel and Unload do the same. Native cancellation is sticky: release and a fresh explicit load are required before another generation. A process restart begins unloaded. Report invitations, reminders and widgets do not generate AI or load the model in the background.

## Fixed iOS-matching models and delivery

| Field | Fast | Balanced |
| --- | --- | --- |
| Author repository | [LiquidAI/LFM2.5-230M-GGUF](https://huggingface.co/LiquidAI/LFM2.5-230M-GGUF) | [LiquidAI/LFM2.5-2.6B-GGUF](https://huggingface.co/LiquidAI/LFM2.5-2.6B-GGUF) |
| Revision | `cdf97bd8205908758f44aec508d68ac1aef98f5c` | `84022ce711b28455e8c4fc364ce68c00cf995875` |
| File | `LFM2.5-230M-QAD-Q4_0.gguf` | `LFM2.5-2.6B-QAD-Q4_0.gguf` |
| Exact size | 149,081,056 bytes, about 149 MB or 142 MiB | 1,593,894,944 bytes, about 1.59 GB or 1.48 GiB |
| SHA-256 | `e75f83268de11b2a1bcfab5f3b5c5c0c97569ddbbc0990aad88437e45b8ba292` | `a247afd6414918eac8e520a9e6137dc271235461ecbe1180462221d5b8d40b03` |
| Minimum total RAM | 2 GiB | 5 GiB |
| Reasoning | No | Yes; final highlights still require valid fact IDs |
| License | [Pinned LFM Open License v1.0](https://huggingface.co/LiquidAI/LFM2.5-230M-GGUF/blob/cdf97bd8205908758f44aec508d68ac1aef98f5c/LICENSE) | [Pinned LFM Open License v1.0](https://huggingface.co/LiquidAI/LFM2.5-2.6B-GGUF/blob/84022ce711b28455e8c4fc364ce68c00cf995875/LICENSE) |
| Download | [Immutable author fast GGUF](https://huggingface.co/LiquidAI/LFM2.5-230M-GGUF/resolve/cdf97bd8205908758f44aec508d68ac1aef98f5c/LFM2.5-230M-QAD-Q4_0.gguf?download=true) | [Immutable author balanced GGUF](https://huggingface.co/LiquidAI/LFM2.5-2.6B-GGUF/resolve/84022ce711b28455e8c4fc364ce68c00cf995875/LFM2.5-2.6B-QAD-Q4_0.gguf?download=true) |

The author's public Hub metadata was checked for each pinned revision, exact LFS size and SHA-256. Both files are public and ungated; no user API key is needed. Their LFM Open License is distinct from Apache/MIT and includes a commercial-use revenue condition; use and distribution remain subject to the linked author terms. Downloading contacts Hugging Face and its allowed HTTPS delivery hosts, which receive ordinary download connection metadata such as the device's IP address. It sends no planner records, report prompts or credentials.

The public store API accepts only these catalog descriptors, which match the fast and balanced entries in iOS `AppConfig.swift`; Android does not include the iOS high preset. The HTTP implementation enforces HTTPS and a narrow redirect allowlist, bounded redirects and connection/read/call timeouts. Compatible partials resume only with a confirmed strong ETag and exact byte range; a server that ignores Range restarts the owned partial. Downloading requires space for the remaining bytes plus a 512 MiB safety reserve. Unknown response lengths remain untrusted until the complete stream has the exact expected size.

The store checks exact size, GGUF magic and the complete SHA-256 before publishing READY or exposing a model path. It fsyncs and atomically renames a verified partial into place; failure and cancellation preserve an existing installed file. Corrupt installed files are preserved for explicit removal/retry, never silently reported ready. Startup and model-path access recheck the stored file on IO.

Weights, partials and bounded delivery metadata live under `noBackupFilesDir/models`. They are unsynced and excluded from Android backup, workspace exports, assistant context and the source repository/APK. Stored metadata contains the catalog identity, size, hash and optional ETag; it does not persist signed CDN URLs or secrets. Uninstalling still removes this private storage and the user's workspace: export the workspace before any reinstall.

## Recorded-fact selection contract

The deterministic Morning, Evening and Weekly reports remain the baseline. The optional action supplies the model with a priority subset of at most 32 recorded fact lines, each bounded to 120 Unicode code points. The final system instruction is 179 characters, and total system/user prompt text remains bounded to 5,000 characters. Coverage warnings explain partial selection and missing evidence in the UI; they are not sent as additional model instructions. Source titles are cleaned and may be shortened; they are data, not instructions. Freeform notes are not supplied to this report-selection prompt.

Facts may include local tasks/assignments, separate preparation and assessment completion, saved appointments, habit schedules/logs and explicitly selected Calendar coverage appropriate to the report period. Calendar use does not infer attendance or free time. Missing logs do not establish missed habits, and estimates do not establish time worked. No cloud AI endpoint is called for this selection.

The model returns only a JSON object shaped like `{"selected":["f0","f2"]}`. These are IDs from the captured fact set, not arbitrary workspace record IDs or app commands. Grammar constrains the candidates; the strict codec independently requires only the `selected` key, at most eight distinct supplied IDs, no unknown IDs, no duplicate keys and at most 2,048 output bytes. The app resolves typed in-memory references to full original source rows with readable dates/status. Compact prompt text stays separate from presentation; missing or changed sources are disclosed rather than reconstructed from that string. It never accepts model-authored factual prose, modifies source records or stages an MCP mutation from this output.

The native bridge bounds context, output and reasoning work and uses up to four CPU threads. Fast has a 1,536-token context: the report action requests 128 output tokens, with a native ceiling of 256. Balanced has a 6,144-token context and a 2,048-token combined reasoning/final-output budget. Native final JSON is capped at 4,096 UTF-8 bytes, then the stricter application codec applies its 2,048-byte limit. Reasoning generation is also byte-bounded. Tokenized input plus reserved output must fit in full; oversize input is rejected rather than silently cutting JSON or facts.

Sampling matches the iOS Liquid profiles: top-k 50, top-p 1, temperature 0.10, seed 1337 and the last 64 tokens for repetition penalties of 1.05 (fast) or 1.10 (balanced). The grammar constrains final-token choices before this sampling chain. The UI shows static generation status and a Cancel action, avoiding a continuously animated renderer during CPU work.

Liquid chat formatting opens the balanced reasoning block and activates the supplied fact-ID grammar after the model's closing `</think>` token. The native code retains only the marker-detection tail rather than returning or logging thoughts. Missing closure, early end-of-sequence or budget exhaustion fails closed. Load and generation each have a 90-second native deadline; generation also has a 95-second controller timeout. Invalid output, timeout, missing model, unavailable native library and memory/load failure leave the recorded report available. A fresh source fingerprint, including report period, device zone, language and Calendar coverage, must still match before late output is displayed. Derived highlights remain in memory and disappear when their source context changes. Generation belongs to the report screen scope, so scrolling its lazy list does not cancel the job. Leaving the report, backgrounding, or explicit cancellation ends the operation.

## Build, notices and verification

Use the repository Gradle wrapper with JDK 21. App builds require SDK platform 37, build tools 36.0.0, NDK 29.0.14206865 and CMake 3.31.6. `./gradlew -PcoreOnly=true :core:test` needs no Android SDK/NDK and covers portable model validation and selection contracts; it does not execute JNI. `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` compiles native code and app/test APKs. Emulator/device instrumentation is a separate check.

The pinned llama.cpp source is revision `5f436dddb440a288ee5611d7d1eca564a6aca9f4` (b10948). Provenance and the narrow privacy log patch are recorded in `app/src/main/cpp/third_party/LLAMA_REVISION.json`. Upstream MIT and retained third-party notices ship in `app/src/main/assets/localai-licenses/`; both Liquid weight licenses are linked above because weights are delivered separately. The application's project-license status remains unchanged. See [third-party notices](licenses/README.md).

Delivery instrumentation uses small synthetic GGUF-like fixtures and a loopback HTTP seam, never downloads real weights and does not prove that a model can infer. Actual JNI tests require a separately downloaded, size/hash-verified model path through the `llamaModelPath` instrumentation argument. Do not copy weights into source, assets or release APKs. Runtime acceptance requires a native-LLDB-attached emulator/device run; ABI compilation and checksum verification alone do not establish successful inference.

The dated 0.3 local app and test APK build passed in 24 seconds. Portable results are 136 passes, zero failures and zero skips, including 18 report-fact tests. An earlier revision passed 50 instrumentation tests in 62.446 seconds: Calendar 18, model delivery 6, native inference 5, controller 1, reminders 8, existing Compose 5 and widgets 7. Its balanced-profile rerun passed all five native and one controller test in 269.195 seconds using the actual 1.59 GB weights and reasoning mode, with no skipped model tests or mock inference. These earlier results are retained as evidence for that revision, rather than attributed to the replacement APK. The replacement APK's full 51-test fast suite passed in 67.15 seconds and its seven-test balanced suite passed in 244.916 seconds. Both include recorded-report inference acceptance, with no failures, errors or skips.

The fast production download UI retained a cancelled 108.5 MB partial, resumed, and reached full verified size/SHA. An earlier fast report UI run selected an original recorded habit-completion fact in 7.21 seconds. Balanced production delivery reached exact size and full SHA, matching iOS. The current balanced UI then displayed the original “Read a page” habit completion for 5 October 2026 in 36.989 seconds. No reasoning text was displayed.

The successful current balanced run used native LLDB attachment before execution, app PID 7674 with tracer PID 7696, and no inference breakpoints. A separate source-tracing run mapped the matching unstripped library using the APK offset and resolved JNI `nativeGenerate` in PID 6088, confirming `max_tokens=2048` and `reasoning=true`. Host LZMA support still limits some system symbols; this is not Kotlin variable-level stepping. No app fatal exception was observed in the accepted UI runs. Expected process exit 9 occurred at earlier runner completion and deliberate force-stop.

Earlier verbose-prompt UI attempts reached the 90-second native deadline and preserved the recorded report through safe fallback, both with greedy decoding and the iOS sampling profile. Direct recorded-fixture inference passed in 36.728 and 40.466 seconds respectively. The final shorter prompt and static status UI completed the real balanced selection above. A debugger pause was diagnostic evidence; it does not prove why the earlier UI attempt was slow.

The first full run had two failures: a test-only competing native-load probe and a real deferred lazy-list state transition in the academic review screen. The probe was removed; captured composition state and stable row keys fixed the review screen. The final 50-test rerun passed, including all existing Compose tests. An earlier incomplete debugger setup after an APK update had an unexplained startup failure and is not counted as acceptance; restored debugger-wait setup preceded the passing final runs.

Physical-phone performance, heat, battery life, extended memory pressure and process-death behaviour remain acceptance work. A fresh remote desktop pairing roundtrip, all-language/dark/accessibility visual coverage and 0.1-to-0.3 data/signing migration were not repeated. The local 0.3 build uses the retained private 0.2 development key; both are incompatible with the published 0.1 key. Export and preserve local data before trying another build; reinstall is not a harmless update workaround. These were pre-publication 0.3 results; the 0.4 release and its publication manifest record the current artifact separately. The model has no CloudKit integration and does not produce the iPhone's free-prose LLM reports.

## Scenarios to verify

| Scenario | Required behaviour |
| --- | --- |
| No model, unsupported ABI or low RAM | Recorded reports remain usable; no automatic download/load or fake AI result. |
| Interrupted/offline download or unknown length | Retain a compatible partial where safe; expose failure/progress; never claim READY before full verification. |
| Bad checksum, oversize response or changed server range | Reject installation and preserve existing installed bytes and workspace data. |
| Cancel/background during load or generation | Signal native cancellation, release safely and require an explicit reload before reuse. |
| Large or hostile source titles | Bound/clean literal facts, disclose the subset and reject oversized complete native input. |
| Changed records, period, language or Calendar coverage | Cancel/invalidate stale selection; display only a result matching the current fingerprint. |
| Invented IDs, duplicates, prose or malformed JSON | Reject the selection; continue showing the recorded-facts report. |
| Memory pressure, timeout or invalid native result | Release the cancelled handle; show retry/fallback without changing planner/history. |
| Reminder/widget/desktop operation alongside AI | Preserve source writes, opt-ins, receipt/Undo semantics and on-demand report invitations. |
