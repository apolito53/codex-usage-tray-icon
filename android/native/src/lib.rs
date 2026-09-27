//! Offline Android compile/link probe for two actual pinned Codex libraries.
//! No auth store is opened. Both request paths are denied by upstream network policy.

use codex_backend_client::Client;
use codex_http_client::{HttpClientFactory, NetworkPolicyController, OutboundProxyPolicy};
use codex_login::{
    AuthCredentialsStoreMode, AuthKeyringBackendKind, AuthRouteConfig, ServerOptions,
};
use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::jstring;

pub fn offline_probe() -> Result<String, String> {
    // Deliberately unsigned synthetic claims, parsed only to exercise upstream code.
    let expiration =
        codex_login::token_data::parse_jwt_expiration("e30.eyJleHAiOjE3MDAwMDAwMDB9.c2FtcGxl")
            .map_err(|error| error.to_string())?
            .ok_or_else(|| "synthetic expiration missing".to_owned())?;

    // A new controller denies all destinations until a policy is published.
    // This experiment never publishes one. Actual request paths must fail closed.
    let controller = NetworkPolicyController::default();
    let factory = HttpClientFactory::new(OutboundProxyPolicy::ReqwestDefault)
        .with_network_policy(controller.policy());
    let client = Client::new_without_redirects("https://offline.invalid", factory.clone());
    let mut login = ServerOptions::new(
        "/offline-unused".into(),
        "offline-synthetic-client".to_owned(),
        /*forced_chatgpt_workspace_id*/ None,
        AuthCredentialsStoreMode::Ephemeral,
        AuthKeyringBackendKind::Direct,
        AuthRouteConfig::from_http_client_factory(factory),
    );
    login.issuer = "https://offline.invalid".to_owned();
    login.open_browser = false;
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| error.to_string())?;
    let login_denial = runtime.block_on(async {
        codex_login::request_device_code(&login)
            .await
            .err()
            .map(|error| error.to_string())
            .ok_or_else(|| "default-deny login request unexpectedly succeeded".to_owned())
    })?;
    let usage_denial = runtime.block_on(async {
        client
            .get_rate_limits()
            .await
            .err()
            .map(|error| error.to_string())
            .ok_or_else(|| "default-deny usage request unexpectedly succeeded".to_owned())
    })?;
    for denial in [&login_denial, &usage_denial] {
        if !denial.contains("application network policy is unavailable") {
            return Err(format!("unexpected offline result: {denial}"));
        }
    }

    Ok(serde_json::json!({
        "mode": "offline-synthetic-only",
        "upstream_commit": include_str!("../upstream-revision.txt").trim(),
        "synthetic_expiration": expiration.timestamp(),
        "backend_discovery_url": client.config_bundle_url(),
        "device_code_request_denied": login_denial,
        "usage_request_denied": usage_denial,
        "credential_store_opened": false,
        "transport_attempted": false,
    })
    .to_string())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_apolito_codexusage_preview_NativeProbe_run(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let probe = std::panic::catch_unwind(offline_probe)
        .unwrap_or_else(|_| Err("Native offline probe panicked".to_owned()));
    let result = probe.unwrap_or_else(|error| {
        serde_json::json!({"mode": "offline-synthetic-only", "error": error}).to_string()
    });
    match env.new_string(result) {
        Ok(value) => value.into_raw(),
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "Native probe result unavailable",
            );
            std::ptr::null_mut()
        }
    }
}
