# Android account-library compile probe

This experiment links the actual `codex-login`, `codex-backend-client`, and
`codex-http-client` crates from OpenAI Codex commit
`8f195c93d7e7acfef95acf273f0e49cce917e291`. It does not run app-server or import
`codex-core`. Upstream source is unchanged.

It contains two offline synthetic diagnostics, not a functional sign-in
implementation. `NativeProbe.run()` parses one fixed unsigned synthetic JWT and calls the upstream
device-code and usage-request methods through an unpublished, default-deny
`NetworkPolicyController`. Both must return the exact upstream unavailable-policy
error. The endpoint is `offline.invalid`; the auth storage mode is ephemeral;
this network probe invokes no auth manager or credential-store operation.

`NativeStorageProbe` separately exercises the real public Codex auth-storage
functions with fixed, deliberately invalid credentials inside a dedicated
synthetic Android Keystore namespace. It performs no real account call or
interactive login flow and returns no credential payload.

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

## Synthetic credential-storage diagnostic

`android_keyring.rs` implements keyring 3.6.3's public `CredentialBuilderApi` and
`CredentialApi`. It holds a global reference to the Java `AndroidCredentialVault`
and a `JavaVM`, attaches each calling thread, bounds temporary JNI references with
local frames, and calls Java `get`, `put`, and `delete`. Only a null read or false
delete becomes `NoEntry`; unavailable storage, missing keys, corrupt ciphertext,
and write failures remain errors. Invalid UTF-8 also becomes a sanitized corrupt
record error instead of retaining payload bytes inside `BadEncoding`.

`NativeStorageProbe.initialize(Object vault, String codexHome)` installs this
builder before publishing initialized state. A process-wide mutex serializes
initialization and every diagnostic operation; commands before initialization
fail. Reinitialization is permitted only for the same vault object and canonical
home. The Java launcher retains that vault for the lifetime of the process.

The fixed namespace is `synthetic-codex-auth`. The canonical home must end in
`com.apolito.codexusage.nativeprobe/no_backup/native-storage-fixtures/synthetic-codex-auth/codex-home`.
The keyring adapter accepts only service `Codex Auth`, no target override, and
the exact `cli|<hash>` account that upstream derives from that canonical home.
This binds the fixture operations to their dedicated location.

`NativeStorageProbe.command(String operation)` accepts `save`, `read`,
`overwrite`, and `delete`. These invoke actual upstream `save_auth`,
`load_auth_dot_json`, and `logout`, always with `AuthCredentialsStoreMode::Keyring`
and `AuthKeyringBackendKind::Direct`. There is no `Auto` or file-storage fallback.
The wrapper rejects an existing or uncheckable `auth.json` before calling upstream
and checks its absence again afterward. Save and overwrite use distinct fixed
fixtures; readback compares the complete upstream object against those fixtures.
No caller can supply a token, arbitrary stored payload, or keyring identity.

The six explicit fault commands are `fault-unavailable-on`,
`fault-unavailable-off`, `fault-fail-next-write`, `fault-tamper-record`,
`fault-remove-key`, and `fault-clear`. They call the Java diagnostic hooks on the
same retained fixture vault under the global mutex. They are intended to be
followed by the ordinary commands so the real upstream error path is observable.
Tamper, key removal, and clear deliberately affect only these synthetic records.

Reports use mode `synthetic-storage-only`, preserve whitelisted operation names,
and return sanitized state (`initial`, `overwritten`, or `absent`). Success
reports include `ok: true`; ordinary commands add `matches_expected: true` and
`auth_file_present: false`, while fault setup adds `fault_applied: true`.
Failures return fixed text and an integer code: Java codes 1–7 remain distinct,
initialization failure is 10, an unexpected or uncheckable auth file is 11,
other upstream/JNI failure is 12, fixture mismatch is 13, invalid command is 14,
and caught panic is 15. The adapter maps Java code 1 to `NoStorageAccess` and the
other Java failures to `PlatformFailure`; none become an empty account state.

The updated JNI library compiles and links for ARM64 and x86_64 Android, with
all three entry points exported. Device persistence, recreation, and fault-path
results are separate runtime checks. The API 30 x86_64 emulator subsequently
passed save/restart/read, overwrite/restart/read, and delete/restart/read.
Injected unavailability and pre-rename write failure, actual ciphertext tampering,
and actual fixture-key removal produced the expected native errors; recovery and
previous-data preservation passed. The isolated Java vault suite also passed.
The app crash buffer was empty. `storage-verification.json` preserves the build
hashes and all 29 sanitized step reports; `verification.json` is the earlier
network-only probe's historical record. These sequential synthetic commands do not
establish production token refresh, late asynchronous write/logout ordering,
TLS trust handling, or real sign-in behavior.

## Notices

Original upstream Apache 2.0 license and attribution files are preserved under
`notices/`. See `licenses/README.md` and `licenses/collect_notices.py` for the
locked dependency notice collection used before distributing a diagnostic APK.
The Codex license alone does not describe every transitive dependency.
