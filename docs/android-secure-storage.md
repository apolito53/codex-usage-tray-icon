# Android secure-storage boundary candidate

September 27, 2026. This is a source review and a proposal for the next bounded
experiment. **No Android credential adapter has been implemented or validated.**
The review did not read account credentials, initiate login, or contact an account
endpoint. Successful native compilation does not establish credential persistence.

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

## Planned validation — not executed

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

Passing these checks would establish the storage boundary only. TLS trust, the
accepted authorization route, live login, refresh, and quota retrieval remain
separate milestones.

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
