//! Fixed synthetic fixtures through the public Codex auth-storage API.

use crate::android_keyring::{AndroidCredentialBuilder, VaultBridge};
use codex_login::{AuthCredentialsStoreMode, AuthDotJson, AuthKeyringBackendKind};
use jni::JNIEnv;
use jni::objects::{JClass, JObject, JString};
use jni::sys::jstring;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::{Arc, Mutex};

const MODE: &str = "synthetic-storage-only";
const HOME_SUFFIX: &str = "com.apolito.codexusage.nativeprobe/no_backup/native-storage-fixtures/synthetic-codex-auth/codex-home";
const OPERATIONS: &[&str] = &[
    "save",
    "read",
    "overwrite",
    "delete",
    "fault-unavailable-on",
    "fault-unavailable-off",
    "fault-fail-next-write",
    "fault-tamper-record",
    "fault-remove-key",
    "fault-clear",
];
static STATE: Mutex<Option<StorageState>> = Mutex::new(None);

struct StorageState {
    bridge: Arc<VaultBridge>,
    home: PathBuf,
}

fn fixture(overwritten: bool) -> Result<AuthDotJson, u8> {
    // These are intentionally invalid, fixed synthetic credentials. No input supplies a token.
    serde_json::from_value(json!({
        "auth_mode": "chatgpt",
        "OPENAI_API_KEY": null,
        "tokens": {
            "id_token": "e30.eyJzdWIiOiJzeW50aGV0aWMtb25seSJ9.c3ludGhldGlj",
            "access_token": if overwritten { "SYNTHETIC_ONLY_ACCESS_V2_NEVER_VALID" } else { "SYNTHETIC_ONLY_ACCESS_V1_NEVER_VALID" },
            "refresh_token": if overwritten { "SYNTHETIC_ONLY_REFRESH_V2_NEVER_VALID" } else { "SYNTHETIC_ONLY_REFRESH_V1_NEVER_VALID" },
            "account_id": "synthetic-not-a-real-account"
        },
        "last_refresh": null
    }))
    .map_err(|_| 12)
}

fn success(operation: &str) -> Value {
    json!({
        "mode": MODE, "operation": operation, "ok": true,
        "backend": "android-keystore-keyring", "storage_mode": "keyring-direct",
        "transport_attempted": false, "credential_payload_returned": false
    })
}

fn failure(operation: &str, code: u8) -> Value {
    let error = match code {
        1 => "storage_unavailable",
        2 => "record_corrupt",
        3 => "key_missing",
        4 => "write_failed",
        5 => "invalid_input",
        6 => "commit_durability_uncertain",
        7 => "cryptography_failed",
        10 => "storage_not_initialized_or_conflicting_initialization",
        11 => "unexpected_auth_file",
        13 => "fixture_mismatch",
        14 => "invalid_operation",
        15 => "native_storage_probe_panicked",
        _ => "upstream_storage_failure",
    };
    json!({"mode": MODE, "operation": operation, "ok": false,
        "error_code": code, "error": error, "transport_attempted": false,
        "credential_payload_returned": false})
}

fn initialize(env: &mut JNIEnv<'_>, vault: JObject<'_>, home: JString<'_>) -> Result<Value, u8> {
    let mut state = STATE.lock().map_err(|_| 10)?;
    if !env
        .is_instance_of(
            &vault,
            "com/apolito/codexusage/preview/storage/AndroidCredentialVault",
        )
        .map_err(|_| 10)?
    {
        return Err(10);
    }
    let home: String = env.get_string(&home).map_err(|_| 10)?.into();
    let home = PathBuf::from(home).canonicalize().map_err(|_| 10)?;
    if !home.is_dir() || !home.ends_with(HOME_SUFFIX) {
        return Err(10);
    }
    if home.join("auth.json").try_exists().map_err(|_| 11)? {
        return Err(11);
    }
    if let Some(existing) = state.as_ref() {
        if existing.home != home
            || !env
                .is_same_object(existing.bridge.vault.as_obj(), &vault)
                .map_err(|_| 10)?
        {
            return Err(10);
        }
    } else {
        let digest = Sha256::digest(home.to_string_lossy().as_bytes());
        let hash = format!("{digest:x}");
        let diagnostics = env
            .find_class("com/apolito/codexusage/preview/storage/StorageFixtureDiagnostics")
            .map_err(|_| 10)?;
        let bridge = Arc::new(VaultBridge {
            vm: env.get_java_vm().map_err(|_| 10)?,
            vault: env.new_global_ref(vault).map_err(|_| 10)?,
            diagnostics: env.new_global_ref(diagnostics).map_err(|_| 10)?,
            expected_user: format!("cli|{}", &hash[..16]),
            last_error: AtomicU8::new(0),
        });
        // The gate remains locked until this finishes. No command can use Android's mock default.
        keyring::set_default_credential_builder(Box::new(AndroidCredentialBuilder(bridge.clone())));
        *state = Some(StorageState { bridge, home });
    }
    let mut report = success("initialize");
    report["initialized"] = json!(true);
    Ok(report)
}

fn command(operation: &str) -> Result<Value, u8> {
    let state = STATE.lock().map_err(|_| 10)?;
    let state = state.as_ref().ok_or(10u8)?;
    if !OPERATIONS.contains(&operation) {
        return Err(14);
    }
    if state.home.join("auth.json").try_exists().map_err(|_| 11)? {
        return Err(11);
    }
    state.bridge.last_error.store(0, Ordering::SeqCst);
    if operation.starts_with("fault-") {
        state.bridge.fixture_fault(operation).map_err(|_| {
            match state.bridge.last_error.load(Ordering::SeqCst) {
                0 => 12,
                code => code,
            }
        })?;
        if state.home.join("auth.json").try_exists().map_err(|_| 11)? {
            return Err(11);
        }
        let mut report = success(operation);
        report["fault_applied"] = json!(true);
        report["auth_file_present"] = json!(false);
        return Ok(report);
    }
    let storage_error = |_| match state.bridge.last_error.load(Ordering::SeqCst) {
        0 => 12,
        code => code,
    };
    let mut report = success(operation);
    match operation {
        "save" | "overwrite" => codex_login::save_auth(
            &state.home,
            &fixture(operation == "overwrite")?,
            AuthCredentialsStoreMode::Keyring,
            AuthKeyringBackendKind::Direct,
        )
        .map_err(storage_error)?,
        "delete" => {
            let removed = codex_login::logout(
                &state.home,
                AuthCredentialsStoreMode::Keyring,
                AuthKeyringBackendKind::Direct,
            )
            .map_err(storage_error)?;
            report["removed"] = json!(removed);
        }
        "read" => {}
        _ => return Err(14),
    }
    let stored = codex_login::load_auth_dot_json(
        &state.home,
        AuthCredentialsStoreMode::Keyring,
        AuthKeyringBackendKind::Direct,
    )
    .map_err(storage_error)?;
    let fixture_state = match stored {
        None => "absent",
        Some(value) if value == fixture(false)? => "initial",
        Some(value) if value == fixture(true)? => "overwritten",
        Some(_) => return Err(13),
    };
    if (operation == "save" && fixture_state != "initial")
        || (operation == "overwrite" && fixture_state != "overwritten")
        || (operation == "delete" && fixture_state != "absent")
    {
        return Err(13);
    }
    if state.home.join("auth.json").try_exists().map_err(|_| 11)? {
        return Err(11);
    }
    report["state"] = json!(fixture_state);
    report["matches_expected"] = json!(true);
    report["auth_file_present"] = json!(false);
    Ok(report)
}

fn result_string(env: &mut JNIEnv<'_>, value: Value) -> jstring {
    // JNI failures are replaced by our sanitized result, never left pending alongside it.
    let _ = env.exception_clear();
    match env.new_string(value.to_string()) {
        Ok(value) => value.into_raw(),
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "Storage diagnostic result unavailable",
            );
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_apolito_codexusage_preview_NativeStorageProbe_initialize(
    mut env: JNIEnv,
    _class: JClass,
    vault: JObject,
    home: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        initialize(&mut env, vault, home)
    }))
    .unwrap_or(Err(15));
    result_string(
        &mut env,
        result.unwrap_or_else(|code| failure("initialize", code)),
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_apolito_codexusage_preview_NativeStorageProbe_command(
    mut env: JNIEnv,
    _class: JClass,
    operation: JString,
) -> jstring {
    let report = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let operation: String = match env.get_string(&operation) {
            Ok(value) => value.into(),
            Err(_) => return failure("command", 14),
        };
        // Reflect only a known command name, never arbitrary caller input.
        let safe_operation = if OPERATIONS.contains(&operation.as_str()) {
            &operation
        } else {
            "command"
        };
        command(&operation).unwrap_or_else(|code| failure(safe_operation, code))
    }))
    .unwrap_or_else(|_| failure("command", 15));
    result_string(&mut env, report)
}
