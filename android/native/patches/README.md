# Android platform HTTPS seam

`prepare-upstream.sh --account` applies `android-account.patch` with `git apply`
to the exact revision in `../upstream-revision.txt`. It is limited to
`codex-http-client`: one Android-only callback module, its exports, the common
transport execution point and Android's existing manual redirect path. No login,
token parsing, refresh, quota decoding or reserve code is replaced.

On Android every `TransportClient` execution goes through the installed callback,
including legacy direct `RequestBuilder` and `ReqwestTransport` paths and any TLS
retry. There is no Android `reqwest::Client::execute` branch. Missing callback,
streaming request bodies, malformed headers, non-HTTPS URLs, URL userinfo and
oversized responses fail before native network I/O. Fixed sanitized builder
errors prevent an upstream TLS retry from treating a callback failure as a TLS
handshake failure. Existing request/default/trace headers are applied before the
callback. Existing policy permits continue to guard transport and body reads;
redirects retain upstream per-hop destination and sensitive-header checks.
Dropping a cancelled request aborts its queued blocking callback; an already
running platform call remains bounded and cannot persist credentials. Preparation
checks all four complete patched blob hashes and rejects unrelated upstream edits,
including edits outside the patch hunks in a patched file.

The platform callback must use Android `HttpsURLConnection` with its ordinary
hostname verifier, trust manager, platform routing and redirects disabled.
Production `AccountPlatform` additionally restricts exact account endpoints,
times out requests and caps response bytes. Neither callback nor patch may log
request bodies, authorization headers or upstream error payloads.

The unpatched offline/native storage laboratory remains the default feature.
`--release --features account --locked` excludes every laboratory JNI entry point
and uses only the production account adapter. All retained upstream notices and
licenses in `../licenses` and `../notices` continue to apply.
