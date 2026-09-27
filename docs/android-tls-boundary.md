# Android TLS boundary: source review and next verification gate

Reviewed 2026-09-27. **This is a source review and a proposed test plan. No TLS adaptation or TLS fixture tests described here have been implemented or executed. No account endpoint was contacted.** The native library loading and default-deny request checks in the separate native lab do not establish certificate verification, working sign-in, or a live usage source.

## Reviewed revisions

The Codex review uses commit [`8f195c93d7e7acfef95acf273f0e49cce917e291`](https://github.com/openai/codex/tree/8f195c93d7e7acfef95acf273f0e49cce917e291). Relevant source files are:

- [`codex-rs/http-client/src/outbound_proxy.rs`](https://github.com/openai/codex/blob/8f195c93d7e7acfef95acf273f0e49cce917e291/codex-rs/http-client/src/outbound_proxy.rs): `HttpClientFactory`, its clone/equality behavior, and proxy/network policy.
- [`codex-rs/http-client/src/client_builder.rs`](https://github.com/openai/codex/blob/8f195c93d7e7acfef95acf273f0e49cce917e291/codex-rs/http-client/src/client_builder.rs): actual reqwest construction, explicit TLS material, and strict versus legacy fallback paths.
- [`codex-rs/http-client/src/client_tls.rs`](https://github.com/openai/codex/blob/8f195c93d7e7acfef95acf273f0e49cce917e291/codex-rs/http-client/src/client_tls.rs): public TLS settings accept one explicit root certificate or a client identity, not a platform certificate verifier.
- [`codex-rs/http-client/src/custom_ca.rs`](https://github.com/openai/codex/blob/8f195c93d7e7acfef95acf273f0e49cce917e291/codex-rs/http-client/src/custom_ca.rs): environment CA bundles and native-root loading.
- [`codex-rs/login/src/outbound_proxy.rs`](https://github.com/openai/codex/blob/8f195c93d7e7acfef95acf273f0e49cce917e291/codex-rs/login/src/outbound_proxy.rs), [`auth/default_client.rs`](https://github.com/openai/codex/blob/8f195c93d7e7acfef95acf273f0e49cce917e291/codex-rs/login/src/auth/default_client.rs), and [`device_code_auth.rs`](https://github.com/openai/codex/blob/8f195c93d7e7acfef95acf273f0e49cce917e291/codex-rs/login/src/device_code_auth.rs): login receives a factory and constructs its own HTTP clients.
- [`codex-rs/backend-client/src/client.rs`](https://github.com/openai/codex/blob/8f195c93d7e7acfef95acf273f0e49cce917e291/codex-rs/backend-client/src/client.rs): usage clients also construct an internal route-aware client pool from that factory.

Registry sources inspected were the pinned `reqwest 0.12.28`, `rustls 0.23.45`, `rustls-native-certs 0.8.3`, and `openssl-probe 0.2.1`. In the last crate, the Android-specific certificate-file candidate is a **Termux path**, not a normal application's Android trust configuration. Bundling OpenSSL fixes compilation; it does not make this path suitable for the APK.

`rustls-platform-verifier 0.7.0` is already in the Codex lockfile for Windows. Its crate archive was checked against the upstream lock checksum `26d1e2536ce4f35f4846aa13bff16bd0ff40157cdb14cc056c7b14ba41233ba0`. Its Android support crate `0.1.1` was checked against `f87165f0995f63a9fbeea62b64d10b4d9d8e78ec6d7d51fb2125fda7bb36788f`. The verifier tag `v/0.7.0` resolves to commit [`996b1c903491641b17b3c9afb65d1352f6fc6b76`](https://github.com/rustls/rustls-platform-verifier/tree/996b1c903491641b17b3c9afb65d1352f6fc6b76). Its source, rather than the partly stale README initialization example, is authoritative for the API: `android::init_with_env`, `init_with_refs`, or `init_with_runtime`; this release uses JNI crate `0.22.4`.

## Confirmed trust-policy issue

The existing public Codex CA interfaces are not an Android platform-verification adapter. Exporting `X509TrustManager.getAcceptedIssuers()` and adding those certificates as rustls roots would retain certificate-chain and hostname checks, but would reduce Android policy to a root snapshot. It would not establish the same per-domain trust rules, distrust decisions, or update behavior as the application trust manager. Setting a CA environment variable also introduces process-wide state and does not solve that policy mismatch.

The published Android component of `rustls-platform-verifier 0.7.0` is useful reference code, but it is **not a drop-in match for this application's Network Security Configuration**:

- Its [`CertificateVerifier.kt`](https://github.com/rustls/rustls-platform-verifier/blob/996b1c903491641b17b3c9afb65d1352f6fc6b76/android/rustls-platform-verifier/src/main/java/org/rustls/platformverifier/CertificateVerifier.kt) opens `KeyStore.getInstance("AndroidCAStore")` and passes that non-null store to `TrustManagerFactory.init`.
- AOSP [`RootTrustManagerFactorySpi.java` at commit `5a1078f40dd511901c33ccf78be6e2d5081d6637`](https://android.googlesource.com/platform/frameworks/base/+/5a1078f40dd511901c33ccf78be6e2d5081d6637/core/java/android/security/net/config/RootTrustManagerFactorySpi.java), blob `0a1fe881cb17b3bb1100fb3a81dedb7961d86c44`, confirms the distinction: a non-null store creates `ApplicationConfig(new KeyStoreConfigSource(ks))`; a null store selects `ApplicationConfig.getDefaultInstance()`. The reviewed current AOSP source and this historical revision have the same Git blob ID.
- Therefore the former does not select the app's default configuration. It must not be described as preserving the APK's domain rules or its default exclusion of user-installed roots. The exact effects on a device still need the fixture tests below.
- Android's [Network Security Configuration documentation](https://developer.android.com/privacy-and-security/security-config) specifies system roots by default for modern target SDKs, with user roots or other anchors explicitly configurable. These distinctions must survive the native transport boundary.

Hostname checking is present in the verifier's Rust code: after Android accepts the chain, it calls `rustls::client::verify_server_name`. Its `verify_tls12_signature` and `verify_tls13_signature` methods also delegate to rustls cryptographic verification. Calling Android's `checkServerTrusted(chain, authType, hostname)` alone must not be treated as equivalent hostname verification; the reference Kotlin source explicitly notes that the hostname is passed for policy such as pinning and CT, while name validation remains in Rust.

The reference Android component also adds PKIX revocation processing that can fetch OCSP/CRL data outside Codex's HTTP factory. A future adaptation must account for that separate network path. Do not enable it and claim that a Codex destination policy proves all transport is offline.

## Bounded adaptation to investigate

The preferred next experiment is a small JNI certificate-verification bridge using the app-default `TrustManagerFactory.init((KeyStore) null)` and `X509TrustManagerExtensions.checkServerTrusted` with the actual destination hostname. Rust must additionally enforce the server name and TLS handshake signatures using rustls. It must propagate Java initialization, class-loading, certificate, and callback failures as verification failures. An alternative is a reviewed upstream change to rustls-platform-verifier that explicitly supports app-default Android trust policy. Merely changing its root list is insufficient.

This does not require rewriting the OAuth or usage HTTP protocols. It does require a narrow Codex HTTP adaptation: **there is no suitable public injection point in the reviewed account constructors today.** `HttpClient::new(reqwest_client)` exists, but the login and backend constructors do not accept that client. `HttpClientTlsConfig` also cannot be installed through the factory these constructors receive.

The smallest maintainable API patch to evaluate is an optional, explicit platform TLS configuration carried by `HttpClientFactory`, preserved in clones and route changes, and applied by the common route-aware reqwest construction path. That configuration can use `reqwest::ClientBuilder::use_preconfigured_tls` with a matching-version rustls `ClientConfig`. Keep the patch in `codex-http-client`; leave the login and backend request logic intact. Construction and verification failures must propagate, and a native-TLS retry or legacy fallback must never discard the selected verifier. Existing explicit-root/client-identity settings and CA environment variables need an explicit conflict rule; for the Android account bridge, rejecting conflicting overrides is preferable to silently changing trust policy. Equality/cache behavior must also distinguish factories with different TLS policy.

This is a proposed patch boundary, not an implemented or compiled patch. The concrete design must be reviewed against every account request path, including device-code start/poll, token exchange/refresh, and usage reads. Allowlisting destinations and rejecting cleartext/credential-forwarding redirects remain separate transport requirements; a manifest declaration alone does not constrain arbitrary native sockets.

## Local verification plan — not executed

First exercise a real rustls client/server handshake entirely in memory inside the APK, with the verifier calling Android's actual trust manager. Use a separate test-only app configuration with a generated local CA scoped to a fixture hostname. Include no account URLs, tokens, Internet permission, AIA URLs, or production CA overrides. This separates certificate-policy proof from network reachability.

| Fixture | Required result |
| --- | --- |
| Valid chain, valid dates, expected fixture hostname, CA present in its test domain configuration | Handshake succeeds and exchanges a fixed synthetic payload. |
| Same chain with an unrelated hostname | Fails specifically at name validation. |
| Same hostname with an unknown CA or self-signed leaf | Fails trust validation. |
| Expired and not-yet-valid leaf certificates | Both fail. Include an expired intermediate as a separate chain check. |
| Wrong server EKU or a tampered certificate/handshake signature | Fails before application data is accepted. |
| Test CA configured for one domain, valid leaf requested under another domain with matching SAN | Fails the other domain's trust rule, proving that root export or app-policy bypass has not occurred. |
| Missing JNI initialization, missing helper class, or thrown Java verifier exception | Fails closed, without OpenSSL/native-root fallback or process crash. |
| Multiple native worker threads and activity recreation | Preserves verification and valid JNI references without attaching policy to an Activity lifetime. |

Then, on an isolated emulator/device, test a user-installed fixture CA with two otherwise equivalent configurations: system-only should reject it; explicit user-root opt-in should accept it. Remove or disable the fixture trust and repeat after process restart. Test any claimed in-process trust-change handling separately; do not claim immediate updates based on a startup snapshot.

Finally, permit only a local fixture server and run the **actual upstream** device-code, token-refresh, and usage HTTP paths against synthetic responses using the adapted factory. Verify every route uses the same verifier, retains the destination policy, rejects a redirect to a forbidden destination, and does not send credentials after TLS failure. Perform this on the oldest supported Android version and a contemporary version, and check the release package has no fixture CA or test trust configuration. Only after these gates should an explicitly initiated real device-code login be attempted.

## What the public authentication evidence establishes

The official [Codex App Server documentation](https://learn.chatgpt.com/docs/app-server) describes custom-product integration, managed ChatGPT login, `chatgptDeviceCode`, frontend ownership of the sign-in UX, and rate-limit reads. Its documented managed flow does not include a separate client-registration step. The official [authentication documentation](https://learn.chatgpt.com/docs/auth) says device-code login must be enabled in the account/workspace and that the user opens the verification link and enters the code.

At the pinned revision, [`account_processor.rs`](https://github.com/openai/codex/blob/8f195c93d7e7acfef95acf273f0e49cce917e291/codex-rs/app-server/src/request_processors/account_processor.rs) builds login options with `oauth_client_id()`. [`login/src/auth/manager.rs`](https://github.com/openai/codex/blob/8f195c93d7e7acfef95acf273f0e49cce917e291/codex-rs/login/src/auth/manager.rs) supplies the upstream public client ID by default and supports an explicit override. `request_device_code` sends the supplied ID through that existing upstream flow. No client secret is needed by that source path.

These facts support investigating a phone-owned session through the real Codex components. They do **not** establish documented support for directly embedding these Rust crates in Android, service acceptance of this particular build, or a general-purpose third-party OAuth registration entitlement. Conversely, absence of Android library documentation is not evidence that separate registration is required or that the route is technically impossible. Preserve the upstream flow, identify the app truthfully, let the user initiate authorization, and report actual service behavior when a later verified build is ready to try it.

The remaining gate for this milestone is concrete: Android app-policy certificate verification has not yet been adapted and tested. Offline native request denial and secure synthetic storage do not close it.
