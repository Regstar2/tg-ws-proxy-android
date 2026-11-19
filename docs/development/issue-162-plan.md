# Issue #162: ARMv7 and Universal APK

All work runs locally on Windows. No push, PR, release, comments, tags or remote CI.
Keep applicationId, minSdk 26, routing behavior and update security unchanged.

1. Compile the unchanged CGO runtime for Android ARMv7/API 26; inspect ELF. Then
   extend the native script to build ARMv7 and ARM64, validate each output and
   remove stale outputs before attempting a build. Restore environment variables.
2. Use Gradle ABI splits for ARMv7, ARM64 and universal APKs for debug/release.
   Keep versionCode equal across ABI outputs for GitHub sideload updates.
   Provide an opt-in isolated debug applicationId for safe device testing.
3. Audit APK ZIP libraries, ELF headers, aapt metadata and apksigner signatures;
   generate local checksums. Adapt build/release/CI/workflow asset handling.
4. Set up a separate API 30 x86 Google APIs AVD and verify its ARM bridge.
   Add a bounded smoke runner and instrumentation for actual 32/64-bit JNA/Go
   calls, listener, SOCKS fixture round trip, stop/restart and service lifecycle.
   Use an explicit serial; preserve the physical phone's production installation.
5. Add failing unit tests for ABI-aware official update selection and actual APK
   compatibility (including universal, unsupported devices, missing JNA, mismatch).
   Implement compatible selection while keeping source/hash/signature/identity
   and monotonic version checks.
6. Run local CI, full Go tests where the Windows toolchain permits, both APK
   builds, runtime smoke on the AVD and suitable USB phone. Record all failures
   and unavailable route tests explicitly. Update RU/EN docs and test report.

Review focus: wrong ELF under a valid ABI directory; stale native outputs after
failure; 64-bit process mistaken for ARMv7 proof; universal with incomplete
native dependencies; updater selecting an incompatible or untrusted APK.

The supplied detailed execution request is the specification and authorizes
implementation without an additional design approval round.
