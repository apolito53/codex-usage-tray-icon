//! Process-scoped JNI credential adapter for the explicitly initialized fixture vault.

use jni::objects::{GlobalRef, JByteArray, JClass, JValue};
use jni::{JNIEnv, JavaVM};
use keyring::credential::{Credential, CredentialApi, CredentialBuilderApi};
use std::any::Any;
use std::fmt;
use std::sync::Arc;
use std::sync::atomic::{AtomicU8, Ordering};

pub(crate) struct VaultBridge {
    pub(crate) vm: JavaVM,
    pub(crate) vault: GlobalRef,
    pub(crate) diagnostics: GlobalRef,
    pub(crate) expected_user: String,
    pub(crate) last_error: AtomicU8,
}

#[derive(Debug)]
struct VaultError(u8);

impl fmt::Display for VaultError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(match self.0 {
            1 => "Android fixture vault unavailable",
            2 => "Android fixture record corrupt",
            3 => "Android fixture key missing",
            4 => "Android fixture write failed",
            5 => "Android fixture input invalid",
            6 => "Android fixture commit durability uncertain",
            7 => "Android fixture cryptography failed",
            _ => "Android fixture JNI failure",
        })
    }
}

impl std::error::Error for VaultError {}

impl VaultBridge {
    pub(crate) fn fixture_fault(&self, operation: &str) -> keyring::Result<()> {
        self.with_env(|env| {
            let class: &JClass = self.diagnostics.as_obj().into();
            let vault = JValue::Object(self.vault.as_obj());
            let (method, signature, args) = match operation {
                "fault-unavailable-on" | "fault-unavailable-off" => (
                    "setUnavailable",
                    "(Lcom/apolito/codexusage/preview/storage/AndroidCredentialVault;Z)V",
                    vec![
                        vault,
                        JValue::Bool(u8::from(operation == "fault-unavailable-on")),
                    ],
                ),
                "fault-fail-next-write" => (
                    "failNextWrite",
                    "(Lcom/apolito/codexusage/preview/storage/AndroidCredentialVault;)V",
                    vec![vault],
                ),
                "fault-tamper-record" => (
                    "tamperOnlyRecord",
                    "(Lcom/apolito/codexusage/preview/storage/AndroidCredentialVault;)V",
                    vec![vault],
                ),
                "fault-remove-key" => (
                    "removeKey",
                    "(Lcom/apolito/codexusage/preview/storage/AndroidCredentialVault;)V",
                    vec![vault],
                ),
                "fault-clear" => (
                    "clearFixture",
                    "(Lcom/apolito/codexusage/preview/storage/AndroidCredentialVault;)V",
                    vec![vault],
                ),
                _ => return Err(self.error(5)),
            };
            env.call_static_method(class, method, signature, &args)
                .map_err(|_| self.java_error(env))?;
            Ok(())
        })
    }

    fn error(&self, code: u8) -> keyring::Error {
        self.last_error.store(code, Ordering::SeqCst);
        match code {
            1 => keyring::Error::NoStorageAccess(Box::new(VaultError(code))),
            _ => keyring::Error::PlatformFailure(Box::new(VaultError(code))),
        }
    }

    fn java_error(&self, env: &mut JNIEnv<'_>) -> keyring::Error {
        let exception = env.exception_occurred().ok();
        let _ = env.exception_clear();
        let code = exception
            .filter(|exception| !exception.is_null())
            .and_then(|exception| {
                env.call_method(exception, "getCode", "()I", &[])
                    .ok()
                    .and_then(|value| value.i().ok())
            })
            .filter(|code| (1..=7).contains(code))
            .map_or(12, |code| code as u8);
        // A foreign Java exception may not have getCode(); never leave its lookup exception pending.
        let _ = env.exception_clear();
        self.error(code)
    }

    fn with_env<T>(
        &self,
        operation: impl FnOnce(&mut JNIEnv<'_>) -> keyring::Result<T>,
    ) -> keyring::Result<T> {
        let mut env = self
            .vm
            .attach_current_thread()
            .map_err(|_| self.error(12))?;
        let result = env.with_local_frame(16, |local_env| {
            Ok::<keyring::Result<T>, jni::errors::Error>(operation(local_env))
        });
        match result {
            Ok(value) => value,
            Err(_) => Err(self.java_error(&mut env)),
        }
    }
}

pub(crate) struct AndroidCredentialBuilder(pub(crate) Arc<VaultBridge>);

impl CredentialBuilderApi for AndroidCredentialBuilder {
    fn build(
        &self,
        target: Option<&str>,
        service: &str,
        user: &str,
    ) -> keyring::Result<Box<Credential>> {
        if target.is_some() || service != "Codex Auth" || user != self.0.expected_user {
            return Err(self.0.error(5));
        }
        let identity =
            serde_json::to_string(&(target, service, user)).map_err(|_| self.0.error(12))?;
        Ok(Box::new(AndroidCredential {
            bridge: self.0.clone(),
            identity,
        }))
    }

    fn as_any(&self) -> &dyn Any {
        self
    }
}

struct AndroidCredential {
    bridge: Arc<VaultBridge>,
    identity: String,
}

impl CredentialApi for AndroidCredential {
    fn get_password(&self) -> keyring::Result<String> {
        String::from_utf8(self.get_secret()?).map_err(|_| self.bridge.error(2))
    }

    fn set_secret(&self, secret: &[u8]) -> keyring::Result<()> {
        if secret.len() > 65_536 {
            return Err(self.bridge.error(5));
        }
        self.bridge.with_env(|env| {
            let identity = env
                .new_string(&self.identity)
                .map_err(|_| self.bridge.java_error(env))?;
            let bytes = env
                .byte_array_from_slice(secret)
                .map_err(|_| self.bridge.java_error(env))?;
            env.call_method(
                self.bridge.vault.as_obj(),
                "put",
                "(Ljava/lang/String;[B)V",
                &[
                    JValue::Object(identity.as_ref()),
                    JValue::Object(bytes.as_ref()),
                ],
            )
            .map_err(|_| self.bridge.java_error(env))?;
            Ok(())
        })
    }

    fn get_secret(&self) -> keyring::Result<Vec<u8>> {
        self.bridge.with_env(|env| {
            let identity = env
                .new_string(&self.identity)
                .map_err(|_| self.bridge.java_error(env))?;
            let value = env
                .call_method(
                    self.bridge.vault.as_obj(),
                    "get",
                    "(Ljava/lang/String;)[B",
                    &[JValue::Object(identity.as_ref())],
                )
                .map_err(|_| self.bridge.java_error(env))?
                .l()
                .map_err(|_| self.bridge.error(12))?;
            if value.is_null() {
                return Err(keyring::Error::NoEntry);
            }
            env.convert_byte_array(JByteArray::from(value))
                .map_err(|_| self.bridge.java_error(env))
        })
    }

    fn delete_credential(&self) -> keyring::Result<()> {
        self.bridge.with_env(|env| {
            let identity = env
                .new_string(&self.identity)
                .map_err(|_| self.bridge.java_error(env))?;
            let removed = env
                .call_method(
                    self.bridge.vault.as_obj(),
                    "delete",
                    "(Ljava/lang/String;)Z",
                    &[JValue::Object(identity.as_ref())],
                )
                .map_err(|_| self.bridge.java_error(env))?
                .z()
                .map_err(|_| self.bridge.error(12))?;
            if removed {
                Ok(())
            } else {
                Err(keyring::Error::NoEntry)
            }
        })
    }

    fn as_any(&self) -> &dyn Any {
        self
    }
}
