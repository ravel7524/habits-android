# Third-party notices

The unmodified Gradle 9.3.1 wrapper is distributed under its upstream licence, reproduced in [gradle-LICENSE.txt](gradle-LICENSE.txt). Wrapper script copyright headers remain intact.

Application dependencies are declared with pinned versions in the build files and retain their own upstream licences. This notice does not assign a licence to the Habits application source.

The CPU JNI backend vendors the selected llama.cpp/ggml CMake source at immutable commit `5f436dddb440a288ee5611d7d1eca564a6aca9f4`. [Its provenance manifest](../../app/src/main/cpp/third_party/LLAMA_REVISION.json) records the archive SHA-256, included files and narrow privacy-log patch. [The upstream MIT notice](../../app/src/main/cpp/third_party/llama.cpp/LICENSE) and retained third-party notices remain in the source. Matching notices are packaged under [localai-licenses](../../app/src/main/assets/localai-licenses/).

Liquid model weights are downloaded separately after an explicit action and are absent from source/APKs. Their LFM Open License v1.0 differs from the native backend's MIT license; see [pinned model licenses and delivery terms](../ANDROID-LOCAL-AI.md#fixed-ios-matching-models-and-delivery).
