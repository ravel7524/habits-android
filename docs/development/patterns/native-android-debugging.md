# Native Android debugger acceptance

Use a debug APK and a task-owned emulator/device. Keep SDK/NDK tools, AVDs and credentials outside the public source tree. Preserve the private development signing key for same-identity updates; never export it.

1. Install the app/test APKs without launching. Push the matching ABI `lldb-server` from an official, verified NDK into a debug-only `code_cache` directory using `run-as`.
2. Start the app with `adb shell am start -D`, or a test runner with its debug wait enabled. Identify the process PID. A PID polling loop must tolerate `pidof` returning nonzero before process creation.
3. Forward a localhost gdb-server port and the process's JDWP port. Start `lldb-server gdbserver 127.0.0.1:<port> --attach <pid>` in the app's debug UID.
4. In host LLDB select the remote Linux/Android platform, create a matching arm64 `app_process64` target and connect using `gdb-remote`. Confirm the correct PID is stopped before continuation.
5. Configure ART's handled signals before tests: pass SIGSEGV, SIGBUS and SIGUSR1 without debugger stops; suppress incidental SIGSTOP stops and allow SIGCHLD. Native application failures still need exit/logcat review. Unsupported debugger setting names are diagnostic errors, not app failures.
6. Continue native execution, then complete a JDWP handshake/VM Resume while retaining the connection long enough to release the managed startup wait. Confirm `/proc/<pid>/status` has a nonzero `TracerPid` during runtime checks.
7. Exercise actual permission dialogs, provider reads, launcher pinning, widget actions and notification UI with UI-tree-derived coordinates. Record test results separately from the interactive walkthrough.
8. Review native status/backtrace and AndroidRuntime/crash logs. Host LLDB may not decode `.gnu_debugdata` without LZMA, leaving partial native symbols. A crash-dump helper diagnostic while ptraced does not alone establish an app crash; verify whether the app stayed alive and whether a Java/native fatal exit occurred.
9. Explicit permission revocation can kill the app UID, and instrumentation normally terminates its target at completion. Label those expected exits and reattach before the next runtime launch. Do not classify a debugger pause/idling timeout as an implementation failure without a clean rerun.
10. Detach, stop only task-owned app/emulator/server processes, clear debugger-wait state, remove task port forwards and delete only owned provider fixtures.

Android 16 notification tests must not assume legacy `POST_NOTIFICATION` AppOps ignore is equivalent to runtime permission denial. Test the actual production permission gate with a delegated context fixture, and verify real grant/refusal/revocation separately; revoking permission inside instrumentation can terminate its UID.

APK updates can clear `code_cache` and debugger-wait state. Recopy the verified server and set the target package's managed debug wait after installation, then reacquire its PID. A missing server or empty PID is a harness failure; do not resume the process to work around it.

Uncompressed native libraries can be mapped directly from `base.apk`, leaving automatic source breakpoints unresolved. Use the APK ZIP entry's data offset and `/proc/<pid>/maps` to identify the library's executable mapping, and the ELF LOAD virtual address to compute its slide. Add the matching unstripped RelWithDebInfo `.so` with `target modules add`, then set the slide with `target modules load --file <so> --slide <address>`. Check the packaged and unstripped build identity before using source frames. The Liquid verification resolved JNI generation this way and observed `max_tokens=2048`, `reasoning=true` on a dispatcher worker. System ART symbols can still be partial.

A breakpoint pause consumes a coroutine's wall-clock allowance. Record source tracing separately, then run real report acceptance with LLDB attached and no inference pauses. Preserve failed/paused runs as diagnostic evidence; do not infer report quality from a fixed-output grammar test alone. The opt-in `RecordedReportInferenceTest` requires a prepared fixture workspace, a verified model path and `recordedReportAcceptance=true`; it logs only counts, ephemeral selection IDs and timing.

Avoid a second native load as a busy probe: its try-lock can make the operation under test fail. Use observable controller lifecycle and the separate native serialization/cancellation tests. After a failed UI-tree dump, discard the previous XML and capture a fresh tree before tapping.

Public builds remap source, build and NDK prefixes with Clang `-ffile-prefix-map` before vendored targets compile. This removes host paths from assert/`__FILE__` literals as well as debug information; stripping alone is insufficient. For native source tracing, map the portable `native` prefix to `app/src/main/cpp` in the matching local checkout. Re-audit packaged library strings after changes to build flags.
