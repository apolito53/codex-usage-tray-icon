# Android secure-storage boundary candidate

September 27, 2026. The source review below now has an implemented
**synthetic-only diagnostic adapter**, with separate runtime verification.
No real account credentials were read, no login was initiated, and no account
endpoint was contacted. Successful compilation alone does not establish
credential persistence or a production authentication lifecycle.

## Reviewed source

The Codex source is pinned to
[`8f195c93d7e7acfef95acf273f0e49cce917e291`][codex-revision]. The relevant paths are:

- [`codex-rs/keyring-store/src/lib.rs`][codex-keyring]: `DefaultKeyringStore`.
- [`codex-rs/login/src/auth/storage.rs`][codex-storage]: direct and automatic
  storage backends, their factory, and credential identity derivation.
- [`codex-rs/login/src/device_code_auth.rs`][codex-device]: device-code completion.
- [`codex-rs/login/src/server.rs`][codex-server]: `persist_tokens_async`.
- [`codex-rs/login/src/auth/manager.rs`][codex-manager]: auth loading, manager
  construction, and refresh persistence.

The resolved `keyring` crate is version **3.6.3**. Its packaged VCS metadata names
commit `315cbdf6c6a9153d8c9f88b56568f29862d3e39d` in `hwchen/keyring-rs`.
The reviewed public interfaces are in [`src/lib.rs`][keyring-lib],
[`src/credential.rs`][keyring-credential], and [`src/error.rs`][keyring-error].

These findings describe those exact revisions. Recheck the boundary when updating
dependencies.

## Public integration hook

`keyring::set_default_credential_builder(Box::new(AndroidCredentialBuilder))`
replaces the process-wide builder used by `keyring::Entry::new`. Upstream
`DefaultKeyringStore` creates entries through that API, so the private Codex
storage factory does not need modification for this candidate.

The adapter would implement `CredentialBuilderApi::build` and `as_any`, plus
`CredentialApi::set_secret`, `get_secret`, `delete_credential`, and `as_any`.
Both trait objects must be `Send + Sync`. The existing string methods encode and
decode UTF-8; persistence would be `CredentialPersistence::UntilDelete`.

**Initialization must fail closed.** This keyring version selects its mock
backend on Android. Without the custom builder, a credential save can appear to
succeed without durable storage. Install the adapter once at process startup and
reject every wrapper auth/storage operation until initialization succeeds. Use
the same resolved keyring crate as upstream, and do not replace the builder while
operations are running.

## Preventing plaintext fallback

The wrapper must explicitly select `AuthCredentialsStoreMode::Keyring` and
`AuthKeyringBackendKind::Direct` for `ServerOptions`, `AuthConfig`, and any direct
save/load/logout calls. Do not expose `File` or `Auto` as configurable choices in
this integration.

At the reviewed revision, device-code completion passes the selected modes to
`persist_tokens_async`, which calls `save_auth`. Managed ChatGPT loading retains
that backend in `ChatgptAuth`, and token refresh writes through the retained
storage. Direct storage does not read or write a plaintext fallback. It does
attempt to remove a legacy `auth.json` after successful save or during deletion.
`AutoAuthStorage`, by contrast, deliberately loads or saves a plaintext file when
keyring access fails. The no-fallback guarantee depends on preserving the explicit
`Keyring`/`Direct` choice throughout the wrapper.

## Candidate Android adapter

Keep an application-context-backed Java/Kotlin adapter reachable from Rust using
a `GlobalRef` and `JavaVM`. A non-exportable AES-GCM key in `AndroidKeyStore` would
encrypt versioned IV/ciphertext records stored under `noBackupFilesDir`. Derive
record identifiers from an unambiguous encoding of target/service/account, rather
than using those strings as filenames. Bind that identity and the record version
as authenticated data, and use a fresh provider-generated IV for each write.

Use atomic replacement for ciphertext and serialize access: Android's
[`AtomicFile`][android-atomic] does not provide thread synchronization. Never write
a plaintext temporary record. The [`Android Keystore`][android-keystore] guide
describes non-exportable keys and recommends keeping cryptographic operations off
the main thread. [`getNoBackupFilesDir`][android-no-backup] excludes its files from
automatic remote backup; do not persist its absolute path because it can change.
Backup and transfer configuration still need device validation before live use.

Do not require per-operation biometric prompts for this background meter. This is
a candidate usability choice, not a claim that all devices supply hardware-backed
keys. Ciphertext with a missing or invalid key must produce an explicit storage
failure; silently creating a replacement key would conceal lost credentials.

Codex derives its credential account identifier from the canonical `codex_home`
path. Create and consistently reuse the app-private home directory before using
the backend. Path relocation must be tested: it can change upstream's identifier,
so startup must not silently treat an existing encrypted session as absent.

## Threads, lifecycle, and errors

The adapter must work from Rust worker threads: device-code persistence uses
`spawn_blocking`, while other storage calls are synchronous inside auth paths.
Keep all auth work off the Android main thread. Share `JavaVM`, not `JNIEnv`;
attach native threads when needed and release their local references. Cache the
adapter object/class during Java-side initialization or `JNI_OnLoad` so worker
threads do not depend on the system class loader finding app classes. Handle a
pending Java exception before further JNI calls. These requirements follow
Android's [JNI guidance][android-jni]. Avoid retaining an Activity reference.

Serialize login completion, refresh, and logout through one owner. In particular,
a late completion or refresh must not recreate a session after logout. Atomic
file replacement alone does not solve that lifecycle race.

Map only a genuinely absent record to `keyring::Error::NoEntry`. Map locked or
inaccessible storage to `NoStorageAccess`; map corrupted ciphertext, missing keys
for existing records, and cryptographic failures to `PlatformFailure`. Keep error
messages and debug output free of credential contents, including malformed bytes.

There are two upstream error-handling details to account for: direct storage
stringifies keyring failures into `io::Error::Other`, and `AuthManager::new`/`shared`
swallow initial auth-load errors. A candidate wrapper should use the fallible
`AuthConfig::load_auth(false)` for preflight and retain sanitized adapter failure
categories for the UI. A locked or corrupted store must not appear as an ordinary
signed-out state. The raw `load_auth_dot_json` helper is available for the synthetic
storage experiment and write-side maintenance.

## Implemented diagnostic boundary

The implementation is in `android/native/src/android_keyring.rs` and
`storage_probe.rs`, with the Java vault and fault controls under
`android/nativeprobe/.../preview/storage/`. Both Android ABIs compile and link.
The APK keeps its separate diagnostic package and has no network permission.

The vault constructor accepts only a bounded `synthetic-*` namespace. The
native bridge further fixes its namespace, canonical home, service, account
identity, and fixture payloads. It exposes no arbitrary credential input.
Initialization retains application-scoped Java references and installs the
custom builder before permitting commands, with a process-wide mutex around
all operations. Activity recreation cannot replace the retained vault.

The native commands exercise actual upstream `save_auth`, `load_auth_dot_json`,
and `logout` through `Keyring`/`Direct`. Fault controls deliberately affect only
those synthetic records. Errors remain distinct and sanitized through JNI;
post-rename durability uncertainty is separate from a rejected pre-rename
write. No real asynchronous login, refresh, or logout pipeline is implemented.

## Validation scope

Use a dedicated synthetic-only test namespace with network denied. Exercise the
real public Codex storage APIs with `Keyring`/`Direct`, not just adapter methods:

1. Save synthetic auth through `save_auth`, kill and restart the app process, then
   read the same record through `load_auth_dot_json`.
2. Overwrite and delete the record; verify deletion persists across restart.
3. Inject locked/unavailable storage, corrupted ciphertext, a missing key, and a
   failed atomic write. Verify failures are visible, prior valid data survives a
   failed write, and no plaintext `auth.json` or temporary record appears.
4. Exercise concurrent operations and cancellation/logout ordering, including a
   delayed write that must not restore a logged-out session.
5. Check initialization failures, process recreation, path relocation, and backup
   or transfer behavior. Confirm no uninitialized call reaches keyring's mock.

These are the acceptance cases; only results explicitly recorded below should
be treated as executed. They establish a storage boundary, not a complete
authentication lifecycle. TLS trust, the accepted authorization route, live
login, refresh, and quota retrieval remain separate milestones.

## Android runtime results

The 0.1.1-lab APK ran on the Android 11/API 30 x86_64 emulator. Initialization
selected the Android Keystore keyring adapter and explicit direct keyring mode.
Real upstream save/read/logout calls passed these independent process-restart
sequences: save then read `initial`, overwrite then read `overwritten`, and
delete then read `absent`. Each restart force-stopped and cold-launched the app;
reopening a Java object was not used as the persistence evidence.

App-private inspection found a hashed-name encrypted record and a lock file,
with no `auth.json`, fixture plaintext marker, or token-field text in those files.
Reports returned only sanitized state and remained synthetic-only, with no
credential payload or HTTP transport attempt. These checks do not establish
hardware-backed key storage, real-device lock behavior, path migration, backup
or transfer behavior, power-loss durability, or asynchronous refresh/logout
ordering. The separate [TLS boundary](android-tls-boundary.md) remains unimplemented.

The isolated Java vault self-check also passed its eight assertions: roundtrip,
non-exportable key, reopening the vault object, preservation after an injected
pre-rename write failure, explicit unavailable-store error, tamper rejection,
missing-key rejection, and overall success. That suite correctly reports
`process_restart_tested: false`; the distinct native sequence above supplies
the process-restart evidence. The unavailable-store case is injected, not a
claim that real device-lock behavior was tested.

The native fault sequence then exercised the actual upstream methods through
JNI: an unavailable-store read returned error 1 and recovered after the fault
was removed; an injected overwrite failure returned error 4 and retained the
previous fixture; tampered ciphertext returned error 2; removing the Keystore
key made both read and overwrite return error 3. Explicit fixture cleanup
restored the absent state. Recovery controls remained usable after errors, and
the final app crash buffer was empty.

The checksummed build record and all 29 sanitized step reports are preserved in
[`android/native/storage-verification.json`](../android/native/storage-verification.json).

[codex-revision]: https://github.com/openai/codex/tree/8f195c93d7e7acfef95acf273f0e49cce917e291
[codex-keyring]: https://github.com/openai/codex/blob/8f195c93d7e7acfef95acf273f0e49cce917e291/codex-rs/keyring-store/src/lib.rs
[codex-storage]: https://github.com/openai/codex/blob/8f195c93d7e7acfef95acf273f0e49cce917e291/codex-rs/login/src/auth/storage.rs
[codex-device]: https://github.com/openai/codex/blob/8f195c93d7e7acfef95acf273f0e49cce917e291/codex-rs/login/src/device_code_auth.rs
[codex-server]: https://github.com/openai/codex/blob/8f195c93d7e7acfef95acf273f0e49cce917e291/codex-rs/login/src/server.rs
[codex-manager]: https://github.com/openai/codex/blob/8f195c93d7e7acfef95acf273f0e49cce917e291/codex-rs/login/src/auth/manager.rs
[keyring-lib]: https://github.com/hwchen/keyring-rs/blob/315cbdf6c6a9153d8c9f88b56568f29862d3e39d/src/lib.rs
[keyring-credential]: https://github.com/hwchen/keyring-rs/blob/315cbdf6c6a9153d8c9f88b56568f29862d3e39d/src/credential.rs
[keyring-error]: https://github.com/hwchen/keyring-rs/blob/315cbdf6c6a9153d8c9f88b56568f29862d3e39d/src/error.rs
[android-keystore]: https://developer.android.com/privacy-and-security/keystore
[android-jni]: https://developer.android.com/ndk/guides/jni-tips
[android-no-backup]: https://developer.android.com/reference/android/content/Context#getNoBackupFilesDir()
[android-atomic]: https://developer.android.com/reference/android/util/AtomicFile
