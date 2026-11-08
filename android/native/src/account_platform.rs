//! Production JNI bridge. No exception messages, response bodies or secrets leave it.

use codex_http_client::{AndroidHttpRequest, AndroidHttpResponse};
use jni::objects::{GlobalRef, JByteArray, JObject, JString, JValue};
use jni::{JNIEnv, JavaVM};
use serde_json::{Map, Value, json};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::sync::atomic::{AtomicU16, Ordering};
use std::sync::{Arc, Mutex, Weak};

pub(super) type Result<T> = std::result::Result<T, &'static str>;

pub(super) struct Platform {
    vm: JavaVM,
    object: GlobalRef,
    pub usage_status: AtomicU16,
    pub device_status: AtomicU16,
    revoke_status: Mutex<HashMap<[u8; 32], Weak<AtomicU16>>>,
}

impl Platform {
    pub fn new(env: &mut JNIEnv<'_>, object: JObject<'_>) -> Result<Self> {
        Ok(Self {
            vm: env.get_java_vm().map_err(|_| "unavailable")?,
            object: env.new_global_ref(object).map_err(|_| "unavailable")?,
            usage_status: AtomicU16::new(0),
            device_status: AtomicU16::new(0),
            revoke_status: Mutex::new(HashMap::new()),
        })
    }

    fn with_env<T>(
        &self,
        failure: &'static str,
        operation: impl FnOnce(&mut JNIEnv<'_>) -> Result<T>,
    ) -> Result<T> {
        let mut env = self.vm.attach_current_thread().map_err(|_| failure)?;
        let result = env.with_local_frame(32, |env| {
            Ok::<Result<T>, jni::errors::Error>(operation(env))
        });
        // Exceptions may contain upstream request details. Clear without inspecting them.
        let exception = env.exception_check().unwrap_or(true);
        if exception || result.is_err() {
            let _ = env.exception_clear();
            return Err(failure);
        }
        result.map_err(|_| failure)?
    }

    pub fn load_secret(&self) -> Result<Option<Vec<u8>>> {
        self.with_env("storage", |env| {
            let value = env
                .call_method(self.object.as_obj(), "loadSecret", "()[B", &[])
                .and_then(|value| value.l())
                .map_err(|_| "storage")?;
            if value.is_null() {
                return Ok(None);
            }
            let bytes = env
                .convert_byte_array(JByteArray::from(value))
                .map_err(|_| "storage")?;
            if bytes.len() > 65_536 {
                return Err("storage");
            }
            Ok(Some(bytes))
        })
    }

    pub fn save_secret(&self, bytes: &[u8]) -> Result<()> {
        if bytes.len() > 65_536 {
            return Err("storage");
        }
        self.with_env("storage", |env| {
            let bytes = env.byte_array_from_slice(bytes).map_err(|_| "storage")?;
            env.call_method(
                self.object.as_obj(),
                "saveSecret",
                "([B)V",
                &[JValue::Object(bytes.as_ref())],
            )
            .map_err(|_| "storage")?;
            Ok(())
        })
    }

    pub fn delete_secret(&self) -> Result<()> {
        self.with_env("storage", |env| {
            env.call_method(self.object.as_obj(), "deleteSecret", "()V", &[])
                .map_err(|_| "storage")?;
            Ok(())
        })
    }

    pub fn watch_revoke(&self, token: &str) -> Result<Arc<AtomicU16>> {
        let status = Arc::new(AtomicU16::new(0));
        let mut watches = self.revoke_status.lock().map_err(|_| "unavailable")?;
        watches.retain(|_, status| status.strong_count() > 0);
        watches.insert(
            Sha256::digest(token.as_bytes()).into(),
            Arc::downgrade(&status),
        );
        Ok(status)
    }

    pub fn request(&self, request: AndroidHttpRequest) -> Result<AndroidHttpResponse> {
        let is_usage =
            request.method == "GET" && request.url == "https://chatgpt.com/backend-api/wham/usage";
        let is_device = request
            .url
            .starts_with("https://auth.openai.com/api/accounts/deviceauth/");
        let revoke_status = if request.url == "https://auth.openai.com/oauth/revoke" {
            request
                .body
                .as_ref()
                .and_then(|body| serde_json::from_slice::<Value>(body).ok())
                .and_then(|body| {
                    body["token"]
                        .as_str()
                        .map(|token| <[u8; 32]>::from(Sha256::digest(token.as_bytes())))
                })
                .and_then(|hash| self.revoke_status.lock().ok()?.get(&hash)?.upgrade())
        } else {
            None
        };
        if is_usage {
            self.usage_status.store(0, Ordering::SeqCst);
        }
        if is_device {
            self.device_status.store(0, Ordering::SeqCst);
        }
        self.with_env("network", |env| {
            let mut headers = Map::new();
            for (name, value) in request.headers {
                headers
                    .entry(name)
                    .or_insert_with(|| json!([]))
                    .as_array_mut()
                    .ok_or("network")?
                    .push(Value::String(value));
            }
            let method = env.new_string(request.method).map_err(|_| "network")?;
            let url = env.new_string(request.url).map_err(|_| "network")?;
            let headers = env
                .new_string(Value::Object(headers).to_string())
                .map_err(|_| "network")?;
            let body = match request.body {
                Some(body) => {
                    JObject::from(env.byte_array_from_slice(&body).map_err(|_| "network")?)
                }
                None => JObject::null(),
            };
            let response = env
                .call_method(
                    self.object.as_obj(),
                    "request",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[B)Ljava/lang/String;",
                    &[
                        JValue::Object(method.as_ref()),
                        JValue::Object(url.as_ref()),
                        JValue::Object(headers.as_ref()),
                        JValue::Object(&body),
                    ],
                )
                .and_then(|value| value.l())
                .map_err(|_| "network")?;
            let response = JString::from(response);
            let response: String = env.get_string(&response).map_err(|_| "network")?.into();
            if response.len() > 2_097_152 {
                return Err("network");
            }
            let response: Value = serde_json::from_str(&response).map_err(|_| "network")?;
            let status = response["status"]
                .as_u64()
                .filter(|value| (100..=599).contains(value))
                .ok_or("network")? as u16;
            if is_usage {
                self.usage_status.store(status, Ordering::SeqCst);
            }
            if is_device {
                self.device_status.store(status, Ordering::SeqCst);
            }
            if let Some(revoke_status) = revoke_status.as_ref() {
                revoke_status.store(status, Ordering::SeqCst);
            }
            let body = response["body"]
                .as_str()
                .ok_or("network")?
                .as_bytes()
                .to_vec();
            if body.len() > 1_048_576 {
                return Err("network");
            }
            let mut headers = Vec::new();
            if let Some(entries) = response["headers"].as_object() {
                for (name, values) in entries {
                    for value in values.as_array().ok_or("network")? {
                        headers.push((name.clone(), value.as_str().ok_or("network")?.to_owned()));
                    }
                }
            }
            Ok(AndroidHttpResponse {
                status,
                headers,
                body,
            })
        })
    }
}
