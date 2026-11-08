//! Account JNI using pinned Codex login, refresh and passive quota implementations.
//!
//! Upstream auth storage is ALWAYS Ephemeral. Each login/session has a unique key;
//! only a current generation can promote its record into Android's atomic vault.
//! State transitions and vault writes share one mutex; network operations never do.

use super::account_platform::{Platform, Result};
use codex_backend_client::Client;
use codex_http_client::{
    DestinationPolicy, HttpClientFactory, NetworkPolicyController, OutboundProxyPolicy,
};
use codex_login::{
    AuthCredentialsStoreMode, AuthDotJson, AuthKeyringBackendKind, AuthManager, AuthRouteConfig,
    CodexAuth, DeviceCode, ServerOptions,
};
use jni::JNIEnv;
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jlong, jstring};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};
use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex, MutexGuard, OnceLock};
use std::time::{Duration, Instant};
use tokio::runtime::Runtime;
use tokio::sync::watch;

const STORE: AuthCredentialsStoreMode = AuthCredentialsStoreMode::Ephemeral;
const KEYRING: AuthKeyringBackendKind = AuthKeyringBackendKind::Direct;
const BACKEND: &str = "https://chatgpt.com/backend-api";
const LOGIN_LIFETIME: Duration = Duration::from_secs(15 * 60);
static APP: OnceLock<Arc<App>> = OnceLock::new();
static INITIALIZE: Mutex<()> = Mutex::new(());

struct App {
    platform: Arc<Platform>,
    home: PathBuf,
    runtime: Runtime,
    state: Mutex<State>,
    // Serializes quota/proactive refresh/401 recovery without blocking cancel/sign-out.
    usage: tokio::sync::Mutex<()>,
}

#[derive(Default)]
struct State {
    initialized: bool,
    vault_fault: bool,
    epoch: u64,
    login: Option<Arc<Attempt>>,
    session: Option<Arc<Session>>,
    durable_hash: Option<[u8; 32]>,
}

struct Attempt {
    id: u64,
    home: PathBuf,
    policy: NetworkPolicyController,
    cancel: watch::Sender<bool>,
    device: Mutex<Option<DeviceCode>>,
    started: Instant,
}

struct Session {
    epoch: u64,
    home: PathBuf,
    policy: NetworkPolicyController,
    manager: Arc<AuthManager>,
    account_key: Option<String>,
}

fn lock<T>(mutex: &Mutex<T>) -> Result<MutexGuard<'_, T>> {
    mutex.lock().map_err(|_| "unavailable")
}

fn digest(bytes: &[u8]) -> [u8; 32] {
    Sha256::digest(bytes).into()
}

fn account_key(record: &AuthDotJson) -> Option<String> {
    let tokens = record.tokens.as_ref()?;
    let workspace = tokens
        .account_id
        .as_deref()
        .or(tokens.id_token.chatgpt_account_id.as_deref())?
        .trim();
    let user = tokens.id_token.chatgpt_user_id.as_deref()?.trim();
    if workspace.is_empty() || user.is_empty() {
        return None;
    }
    let identity = serde_json::to_vec(&("codex-android-account-v1", workspace, user)).ok()?;
    Some(format!("{:x}", Sha256::digest(identity)))
}

fn validate_record(record: &AuthDotJson) -> Result<()> {
    let tokens = record.tokens.as_ref().ok_or("needs_login")?;
    if tokens.access_token.is_empty()
        || tokens.refresh_token.is_empty()
        || record.openai_api_key.is_some()
        || record.agent_identity.is_some()
        || record.personal_access_token.is_some()
        || record.bedrock_api_key.is_some()
        || record.bedrock_access_keys.is_some()
    {
        return Err("needs_login");
    }
    Ok(())
}

fn policy() -> NetworkPolicyController {
    let controller = NetworkPolicyController::default();
    let revision = controller.policy().revision();
    controller.publish(
        revision,
        DestinationPolicy::Restricted {
            allowed_hosts: BTreeSet::from(["auth.openai.com".to_owned(), "chatgpt.com".to_owned()]),
        },
    );
    controller
}

fn route(policy: &NetworkPolicyController) -> AuthRouteConfig {
    AuthRouteConfig::from_http_client_factory(
        HttpClientFactory::new(OutboundProxyPolicy::ReqwestDefault)
            .with_network_policy(policy.policy()),
    )
}

fn options(attempt: &Attempt) -> ServerOptions {
    let mut options = ServerOptions::new(
        attempt.home.clone(),
        codex_login::oauth_client_id(),
        None,
        STORE,
        KEYRING,
        route(&attempt.policy),
    );
    options.open_browser = false;
    options
}

fn erase(home: &Path) {
    let _ = codex_login::logout(home, STORE, KEYRING);
}

fn stop_attempt(attempt: Arc<Attempt>) {
    attempt.cancel.send_replace(true);
    attempt.policy.policy().invalidate();
    erase(&attempt.home);
}

impl App {
    fn snapshot(&self) -> Result<Value> {
        let state = lock(&self.state)?;
        if !state.initialized || state.vault_fault {
            return Err("storage");
        }
        let mut value = json!({"ok":true, "connected":state.session.is_some()});
        if let Some(key) = state
            .session
            .as_ref()
            .and_then(|session| session.account_key.as_ref())
        {
            value["accountKey"] = json!(key);
        }
        Ok(value)
    }

    fn restore(&self) -> Result<Value> {
        if lock(&self.state)?.initialized {
            return self.snapshot();
        }
        let secret = self.platform.load_secret()?;
        let session = if let Some(bytes) = secret.as_ref() {
            let record: AuthDotJson = serde_json::from_slice(bytes).map_err(|_| "storage")?;
            validate_record(&record)?;
            Some(self.runtime.block_on(self.make_session(
                0,
                self.home.join("session-0"),
                policy(),
                &record,
            ))?)
        } else {
            None
        };
        let mut state = lock(&self.state)?;
        // A concurrent sign-out may have initialized/advanced state while load ran.
        if !state.initialized && state.epoch == 0 {
            state.session = session;
            state.durable_hash = secret.as_ref().map(|bytes| digest(bytes));
            state.initialized = true;
            state.vault_fault = false;
        }
        drop(state);
        self.snapshot()
    }

    async fn make_session(
        &self,
        epoch: u64,
        home: PathBuf,
        policy: NetworkPolicyController,
        record: &AuthDotJson,
    ) -> Result<Arc<Session>> {
        validate_record(record)?;
        codex_login::save_auth(&home, record, STORE, KEYRING).map_err(|_| "storage")?;
        let manager = AuthManager::shared(
            home.clone(),
            false,
            STORE,
            None,
            Some(BACKEND.to_owned()),
            KEYRING,
            route(&policy),
        )
        .await;
        if !matches!(manager.auth_cached(), Some(CodexAuth::Chatgpt(_))) {
            erase(&home);
            return Err("needs_login");
        }
        Ok(Arc::new(Session {
            epoch,
            home,
            policy,
            manager,
            account_key: account_key(record),
        }))
    }

    fn next_epoch(state: &mut State) -> Result<u64> {
        state.epoch = state
            .epoch
            .checked_add(1)
            .filter(|value| *value <= i64::MAX as u64)
            .ok_or("unavailable")?;
        Ok(state.epoch)
    }

    fn clear_active(state: &mut State) {
        if let Some(attempt) = state.login.take() {
            stop_attempt(attempt);
        }
        if let Some(session) = state.session.take() {
            session.policy.policy().invalidate();
            erase(&session.home);
        }
        state.durable_hash = None;
    }

    fn start_login(&self) -> Result<Value> {
        let attempt = {
            let mut state = lock(&self.state)?;
            if !state.initialized {
                return Err("storage");
            }
            let id = Self::next_epoch(&mut state)?;
            Self::clear_active(&mut state);
            // Deletion is serialized with every successful credential promotion.
            if self.platform.delete_secret().is_err() {
                state.vault_fault = true;
                return Err("storage");
            }
            state.vault_fault = false;
            let attempt = Arc::new(Attempt {
                id,
                home: self.home.join(format!("login-{id}")),
                policy: policy(),
                cancel: watch::channel(false).0,
                device: Mutex::new(None),
                started: Instant::now(),
            });
            state.login = Some(attempt.clone());
            attempt
        };
        let mut cancel = attempt.cancel.subscribe();
        self.platform.device_status.store(0, Ordering::SeqCst);
        let result = self.runtime.block_on(async {
            let options = options(&attempt);
            if *cancel.borrow() {
                return Err("cancelled");
            }
            tokio::select! {
                biased;
                _ = cancel.changed() => Err("cancelled"),
                result = codex_login::request_device_code(&options) =>
                    result.map_err(|_| self.device_error()),
            }
        });
        match result {
            Ok(device) => {
                let state = lock(&self.state)?;
                if !Self::is_attempt(&state, attempt.id) {
                    return Err("cancelled");
                }
                let result = json!({"ok":true,"attempt":attempt.id,"userCode":device.user_code,"verificationUrl":device.verification_url});
                *lock(&attempt.device)? = Some(device);
                Ok(result)
            }
            Err(code) => {
                self.abandon(attempt.id);
                Err(code)
            }
        }
    }

    fn is_attempt(state: &State, id: u64) -> bool {
        state.epoch == id && state.login.as_ref().is_some_and(|attempt| attempt.id == id)
    }

    fn abandon(&self, id: u64) {
        if let Ok(mut state) = lock(&self.state) {
            if Self::is_attempt(&state, id) {
                if let Some(attempt) = state.login.take() {
                    stop_attempt(attempt);
                }
            }
        }
    }

    fn device_error(&self) -> &'static str {
        match self.platform.device_status.load(Ordering::SeqCst) {
            429 => "rate_limited",
            401 | 403 | 404 => "expired",
            200..=299 => "unavailable",
            _ => "network",
        }
    }

    fn finish_login(&self, id: u64) -> Result<Value> {
        let attempt = {
            let state = lock(&self.state)?;
            if !Self::is_attempt(&state, id) {
                return Err("cancelled");
            }
            state.login.clone().ok_or("cancelled")?
        };
        let device = lock(&attempt.device)?.take().ok_or("cancelled")?;
        let remaining = LOGIN_LIFETIME
            .checked_sub(attempt.started.elapsed())
            .ok_or("expired")?;
        let mut cancel = attempt.cancel.subscribe();
        let completed = self.runtime.block_on(async {
            if *cancel.borrow() { return Err("cancelled"); }
            tokio::select! {
                biased;
                _ = cancel.changed() => Err("cancelled"),
                result = tokio::time::timeout(remaining, codex_login::complete_device_code_login(options(&attempt), device)) =>
                    match result { Ok(result) => result.map_err(|_| self.device_error()), Err(_) => Err("expired") },
            }
        });
        if let Err(code) = completed {
            self.abandon(id);
            return Err(code);
        }
        let record = codex_login::load_auth_dot_json(&attempt.home, STORE, KEYRING)
            .map_err(|_| "storage")?
            .ok_or("storage")?;
        let session = self.runtime.block_on(self.make_session(
            id,
            attempt.home.clone(),
            attempt.policy.clone(),
            &record,
        ))?;
        let bytes = serde_json::to_vec(&record).map_err(|_| "storage")?;
        let mut state = lock(&self.state)?;
        if !Self::is_attempt(&state, id) {
            erase(&attempt.home);
            return Err("cancelled");
        }
        if self.platform.save_secret(&bytes).is_err() {
            state.vault_fault = true;
            if let Some(attempt) = state.login.take() {
                stop_attempt(attempt);
            }
            let _ = self.platform.delete_secret();
            return Err("storage");
        }
        state.login = None;
        state.durable_hash = Some(digest(&bytes));
        state.session = Some(session.clone());
        let mut result = json!({"ok":true,"connected":true});
        if let Some(key) = session.account_key.as_ref() {
            result["accountKey"] = json!(key);
        }
        Ok(result)
    }

    fn cancel_login(&self, id: u64) -> Result<Value> {
        let mut state = lock(&self.state)?;
        if Self::is_attempt(&state, id) {
            Self::next_epoch(&mut state)?;
            if let Some(attempt) = state.login.take() {
                stop_attempt(attempt);
            }
        }
        let connected = state
            .session
            .as_ref()
            .is_some_and(|session| session.epoch == id);
        Ok(json!({"ok":true,"connected":connected}))
    }

    fn current_session(&self) -> Result<Arc<Session>> {
        let state = lock(&self.state)?;
        if state.vault_fault || !state.initialized {
            return Err("storage");
        }
        state.session.clone().ok_or("signed_out")
    }

    fn check_session(state: &State, session: &Session) -> Result<()> {
        if state.epoch != session.epoch
            || state
                .session
                .as_ref()
                .is_none_or(|current| current.epoch != session.epoch)
        {
            return Err("cancelled");
        }
        Ok(())
    }

    // Called after upstream proactive and 401 refresh, BEFORE using rotated tokens.
    fn persist_session(&self, session: &Session) -> Result<()> {
        let record = codex_login::load_auth_dot_json(&session.home, STORE, KEYRING)
            .map_err(|_| "storage")?
            .ok_or("needs_login")?;
        validate_record(&record)?;
        let bytes = serde_json::to_vec(&record).map_err(|_| "storage")?;
        let hash = digest(&bytes);
        let mut state = lock(&self.state)?;
        Self::check_session(&state, session)?;
        if state.durable_hash != Some(hash) {
            if self.platform.save_secret(&bytes).is_err() {
                state.vault_fault = true;
                Self::clear_active(&mut state);
                let _ = self.platform.delete_secret();
                return Err("storage");
            }
            state.durable_hash = Some(hash);
        }
        Ok(())
    }

    fn require_login(&self, session: &Session) -> Result<Value> {
        let mut state = lock(&self.state)?;
        Self::check_session(&state, session)?;
        Self::next_epoch(&mut state)?;
        Self::clear_active(&mut state);
        if self.platform.delete_secret().is_err() {
            state.vault_fault = true;
            return Err("storage");
        }
        Err("needs_login")
    }

    fn read_usage(&self) -> Result<Value> {
        self.runtime.block_on(async {
            let _usage = self.usage.lock().await;
            let session = self.current_session()?;
            let mut auth = session.manager.auth().await.ok_or("needs_login")?;
            if session.manager.refresh_failure_for_auth(&auth).is_some() {
                return self.require_login(&session);
            }
            self.persist_session(&session)?;
            for retry in 0..=1 {
                let mut client = Client::from_auth(
                    BACKEND,
                    &auth,
                    route(&session.policy).http_client_factory().clone(),
                );
                if let Some(account) = auth.get_account_id() {
                    client = client.with_chatgpt_account_id(account);
                }
                if auth.is_fedramp_account() {
                    client = client.with_fedramp_routing_header();
                }
                self.platform.usage_status.store(0, Ordering::SeqCst);
                // The passive upstream API deliberately does not opt into Luna reserve.
                let result = client.get_rate_limits().await;
                {
                    let state = lock(&self.state)?;
                    Self::check_session(&state, &session)?;
                }
                match result {
                    Ok(snapshot) => {
                        let weekly = snapshot
                            .secondary
                            .iter()
                            .chain(snapshot.primary.iter())
                            .find(|window| window.window_minutes == Some(7 * 24 * 60));
                        let remaining = weekly
                            .filter(|window| window.used_percent.is_finite())
                            .map(|window| {
                                (100.0 - window.used_percent).clamp(0.0, 100.0).round() as i32
                            });
                        let resets = weekly
                            .and_then(|window| window.resets_at)
                            .filter(|value| *value > 0);
                        let mut value =
                            json!({"ok":true,"remaining":remaining,"resetsAtSeconds":resets});
                        if let Some(key) = session.account_key.as_ref() {
                            value["accountKey"] = json!(key);
                        }
                        return Ok(value);
                    }
                    Err(_)
                        if self.platform.usage_status.load(Ordering::SeqCst) == 401
                            && retry == 0 =>
                    {
                        match session.manager.refresh_token().await {
                            Ok(()) => (),
                            Err(codex_login::RefreshTokenError::Permanent(_)) => {
                                return self.require_login(&session);
                            }
                            Err(codex_login::RefreshTokenError::Transient(_)) => {
                                return Err("network");
                            }
                            Err(codex_login::RefreshTokenError::Policy(_)) => {
                                return Err("cancelled");
                            }
                        }
                        self.persist_session(&session)?;
                        auth = session.manager.auth_cached().ok_or("needs_login")?;
                    }
                    Err(_) => {
                        return match self.platform.usage_status.load(Ordering::SeqCst) {
                            401 => self.require_login(&session),
                            403 => Err("unavailable"),
                            429 => Err("rate_limited"),
                            200..=299 => Err("unavailable"),
                            _ => Err("network"),
                        };
                    }
                }
            }
            Err("unavailable")
        })
    }

    fn sign_out(&self) -> Result<Value> {
        let (record, epoch) = {
            let mut state = lock(&self.state)?;
            let record = state.session.as_ref().and_then(|session| {
                codex_login::load_auth_dot_json(&session.home, STORE, KEYRING)
                    .ok()
                    .flatten()
            });
            let epoch = Self::next_epoch(&mut state)?;
            Self::clear_active(&mut state);
            state.initialized = true;
            if self.platform.delete_secret().is_err() {
                state.vault_fault = true;
                return Err("storage");
            }
            state.vault_fault = false;
            (record, epoch)
        };
        // Local removal is already durable. Revoke only this detached snapshot;
        // upstream cleanup can never delete a new login/session or the vault.
        let mut revocation_confirmed = false;
        if let Some(record) = record {
            let home = self.home.join(format!("revoke-{epoch}"));
            if codex_login::save_auth(&home, &record, STORE, KEYRING).is_ok() {
                let observed = record
                    .tokens
                    .as_ref()
                    .and_then(|tokens| self.platform.watch_revoke(&tokens.refresh_token).ok());
                let _ = self.runtime.block_on(codex_login::logout_with_revoke(
                    &home,
                    STORE,
                    KEYRING,
                    &route(&policy()),
                ));
                revocation_confirmed = observed
                    .is_some_and(|status| matches!(status.load(Ordering::SeqCst), 200 | 204));
                erase(&home);
            }
        }
        Ok(json!({"ok":true,"connected":false,"revocationConfirmed":revocation_confirmed}))
    }
}

fn app() -> Result<&'static Arc<App>> {
    APP.get().ok_or("unavailable")
}

fn initialize<'local>(
    env: &mut JNIEnv<'local>,
    platform: JObject<'local>,
    home: JString<'local>,
) -> Result<Value> {
    let _initialize = lock(&INITIALIZE)?;
    let home: String = env.get_string(&home).map_err(|_| "unavailable")?.into();
    let home = PathBuf::from(home);
    if !home.is_absolute() {
        return Err("unavailable");
    }
    if APP.get().is_none() {
        // Suppress panic payloads that upstream could include in its diagnostic output.
        std::panic::set_hook(Box::new(|_| {}));
        codex_login::default_client::set_default_originator("codex_usage_meter_android".to_owned())
            .map_err(|_| "unavailable")?;
        let platform = Arc::new(Platform::new(env, platform)?);
        let transport = platform.clone();
        codex_http_client::install_android_http_transport(move |request| {
            transport.request(request).map_err(|_| ())
        })
        .map_err(|_| "unavailable")?;
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .max_blocking_threads(8)
            .enable_all()
            .build()
            .map_err(|_| "unavailable")?;
        APP.set(Arc::new(App {
            platform,
            home: home.clone(),
            runtime,
            state: Mutex::new(State::default()),
            usage: tokio::sync::Mutex::new(()),
        }))
        .map_err(|_| "unavailable")?;
    }
    let app = app()?;
    if app.home != home {
        return Err("unavailable");
    }
    app.restore()
}

fn output<'local>(
    env: &mut JNIEnv<'local>,
    operation: impl FnOnce(&mut JNIEnv<'local>) -> Result<Value>,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| operation(env)))
        .unwrap_or(Err("unavailable"));
    let _ = env.exception_clear();
    let value = match result {
        Ok(value) => value,
        Err(error) => json!({"ok":false,"error":error}),
    };
    env.new_string(value.to_string())
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_apolito_codexusage_preview_NativeAccount_initialize<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    platform: JObject<'local>,
    home: JString<'local>,
) -> jstring {
    output(&mut env, |env| initialize(env, platform, home))
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_apolito_codexusage_preview_NativeAccount_startLogin(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    output(&mut env, |_| app()?.start_login())
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_apolito_codexusage_preview_NativeAccount_finishLogin(
    mut env: JNIEnv,
    _class: JClass,
    attempt: jlong,
) -> jstring {
    output(&mut env, |_| app()?.finish_login(attempt as u64))
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_apolito_codexusage_preview_NativeAccount_cancelLogin(
    mut env: JNIEnv,
    _class: JClass,
    attempt: jlong,
) -> jstring {
    output(&mut env, |_| app()?.cancel_login(attempt as u64))
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_apolito_codexusage_preview_NativeAccount_readUsage(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    output(&mut env, |_| app()?.read_usage())
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_apolito_codexusage_preview_NativeAccount_signOut(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    output(&mut env, |_| app()?.sign_out())
}
