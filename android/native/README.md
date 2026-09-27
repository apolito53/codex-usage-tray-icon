# Android account-library compile probe

This experiment links the actual `codex-login`, `codex-backend-client`, and
`codex-http-client` crates from OpenAI Codex commit
`8f195c93d7e7acfef95acf273f0e49cce917e291`. It does not run app-server or import
`codex-core`. Upstream source is unchanged.

It is an offline synthetic test, not a functional sign-in implementation. The
JNI function parses one fixed unsigned synthetic JWT and calls the upstream
device-code and usage-request methods through an unpublished, default-deny
`NetworkPolicyController`. Both must return the exact upstream unavailable-policy
error. The endpoint is `offline.invalid`; the auth storage mode is ephemeral;
no auth manager or credential-store operation is invoked. The probe does not
include a real account call or an interactive login flow.

Successful compilation alone does not establish Android TLS trust, Android
credential persistence, accepted authorization, completed login, token refresh,
or live quota retrieval. Running the probe additionally establishes execution of
the linked request code up to its application-policy denial boundary.

## Layout and toolchain

`prepare-upstream.sh` creates an ignored checkout at `.upstream/codex`, pinned by
`upstream-revision.txt`, with all of `codex-rs` materialized. The relative paths
in `Cargo.toml` point at its original crate manifests. The build script checks
the source revision and rejects a modified checkout before compiling.

Use Rust 1.95.0 with `aarch64-linux-android` and `x86_64-linux-android` targets,
Android NDK 28.2.13676358, and API 26. The build script limits jobs to two and
disables development debug info and incremental artifacts. Set `CARGO_HOME` and
`RUSTUP_HOME` if Rust is installed outside your default locations, and put the
matching Cargo binary on `PATH`.

```bash
export ANDROID_NDK_HOME=/absolute/path/to/android-sdk/ndk/28.2.13676358
bash check-android.sh
PROBE_TARGET=x86_64-linux-android PROBE_ACTION=build bash check-android.sh
```

The committed lockfile is seeded from upstream's lockfile, then extended only
for the probe dependencies. `PROBE_UPDATE_LOCK=1` permits an intentional lockfile
update; ordinary verification uses `--locked`. Upstream's relevant Cargo patches
are repeated because Cargo does not inherit patches from a path dependency's
workspace. The unused `crossterm` patch warning is expected.

## Actual build findings

`cargo check --locked --target aarch64-linux-android` passes with the NDK and
vendored-OpenSSL setup below. No upstream Rust source or manifest was modified.
The ARM64 and x86_64 Android shared-library builds also pass. Their exported JNI
symbol is present, all load segments use 16 KB alignment, and their only dynamic
dependencies are Android's `libc.so`, `libm.so`, and `libdl.so`. The development build is
unoptimized and should not be used to estimate the eventual production size.

An actual Android API 30 x86_64 emulator APK loaded the JNI library and passed
the offline probe: the report contained the pinned commit, synthetic expiration
`1700000000`, and the exact unavailable-policy denial for both the device-code
and quota-request paths. It reported no credential-store access or transport
attempt, and the process crash buffer was empty. This validates the exercised
offline paths on Android; it does not validate phone hardware or real sign-in.

An initial check without cross-compiler environment variables failed in
`aws-lc-sys` because it could not find `aarch64-linux-android-clang`. The script
supplies the NDK compiler, archiver, and linker explicitly.

With the NDK configured, unmodified dependencies failed at `openssl-sys 0.9.111`:
the Android OpenSSL library could not be found, and host `pkg-config` was not
configured for cross-compilation. The probe enables the ordinary `vendored`
feature of `openssl`, which unifies with upstream's dependency and builds the
library for Android. This requires no upstream source change. It does not settle
the separate runtime certificate-trust question.

The Android graph contains hundreds of packages, including build dependencies,
Starlark, AWS authentication support, image handling, state/rollout
storage, and PTY support. The apparent two-crate boundary is therefore much wider
than it looks. A later production design should evaluate focused upstream
extraction or feature gating rather than copying an entire app-server runtime.

## JNI boundary

`Java_com_apolito_codexusage_preview_NativeProbe_run` returns a JSON string.
Load `libcodex_android_native_probe.so`, then call a static native `String run()`
on `com.apolito.codexusage.preview.NativeProbe`. A successful result reports
`mode: offline-synthetic-only`, the pinned source commit, synthetic expiration
`1700000000`, and both policy-denial messages. An `error` member indicates that
the expected boundary was not reached.

Any test APK containing this experiment should keep this action explicit and
label it as offline diagnostics. This is not a replacement for the existing
working notification preview.

## Notices

Original upstream Apache 2.0 license and attribution files are preserved under
`notices/`. See `licenses/README.md` and `licenses/collect_notices.py` for the
locked dependency notice collection used before distributing a diagnostic APK.
The Codex license alone does not describe every transitive dependency.
