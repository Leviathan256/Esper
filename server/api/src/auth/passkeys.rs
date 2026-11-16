//! Passkey registration and discoverable sign-in (docs/SERVER.md §6).

use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

use axum::{
    Json,
    extract::{State, rejection::JsonRejection},
};
use serde::Deserialize;
use serde_json::{Value, json};
use sqlx::PgPool;
use webauthn_rs::prelude::*;

use super::sessions;
use crate::error::ApiError;

const CEREMONY_TTL: Duration = Duration::from_secs(5 * 60);

enum Ceremony {
    Register(Uuid, PasskeyRegistration),
    Login(DiscoverableAuthentication),
}

pub struct Passkeys {
    webauthn: Webauthn,
    /// shortcut: in-memory, restart drops in-flight ceremonies, single instance only; move to a table if we ever run two
    ceremonies: Mutex<HashMap<String, (Instant, Ceremony)>>,
}

impl Passkeys {
    pub fn new(rp_id: &str, rp_origins: &[Url]) -> Result<Self, WebauthnError> {
        let rp_origin =
            Url::parse(&format!("https://{rp_id}")).map_err(|_| WebauthnError::Configuration)?;
        let mut builder = WebauthnBuilder::new(rp_id, &rp_origin)?.rp_name("Esper");
        for origin in rp_origins {
            builder = builder.append_allowed_origin(origin);
        }
        Ok(Self {
            webauthn: builder.build()?,
            ceremonies: Mutex::default(),
        })
    }

    fn put(&self, ceremony: Ceremony, now: Instant) -> String {
        let mut raw = [0u8; 16];
        getrandom::fill(&mut raw).expect("OS random source");
        let id = base64::Engine::encode(&base64::engine::general_purpose::URL_SAFE_NO_PAD, raw);
        let mut map = self.ceremonies.lock().unwrap();
        map.insert(id.clone(), (now + CEREMONY_TTL, ceremony));
        id
    }

    /// Removes on read, so a replayed id fails even inside its TTL.
    fn take(&self, id: &str, now: Instant) -> Option<Ceremony> {
        let mut map = self.ceremonies.lock().unwrap();
        map.remove(id)
            .filter(|(expires, _)| now < *expires)
            .map(|(_, c)| c)
    }

    pub fn purge(&self, now: Instant) {
        let mut map = self.ceremonies.lock().unwrap();
        map.retain(|_, (expires, _)| now < *expires);
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VerifyBody {
    ceremony_id: String,
    /// The JSON string androidx.credentials hands back, passed through verbatim.
    response: String,
}

/// Any webauthn failure, logged by kind only: the response itself is never logged.
fn rejected(e: impl std::fmt::Display) -> ApiError {
    tracing::warn!("passkey rejected: {e}");
    ApiError::PASSKEY_REJECTED
}

/// `{ceremonyId, publicKey}`: androidx.credentials wants the inner `publicKey` object.
fn options(ceremony_id: String, challenge: impl serde::Serialize) -> Json<Value> {
    let mut outer = serde_json::to_value(challenge).expect("challenge serializes");
    Json(json!({"ceremonyId": ceremony_id, "publicKey": outer["publicKey"].take()}))
}

pub async fn register_options(
    State(passkeys): State<Arc<Passkeys>>,
) -> Result<Json<Value>, ApiError> {
    let account_id = Uuid::new_v4();
    let name = format!("esper-{}", &account_id.simple().to_string()[..8]);
    let (ccr, registration) = passkeys
        .webauthn
        .start_passkey_registration(account_id, &name, "Esper player", None)
        .map_err(|e| {
            tracing::error!("start registration: {e}");
            ApiError::INTERNAL
        })?;
    let id = passkeys.put(Ceremony::Register(account_id, registration), Instant::now());
    let Json(mut body) = options(id, ccr);
    // webauthn-rs asks for a non-discoverable credential; Esper signs in without a username.
    let selection = &mut body["publicKey"]["authenticatorSelection"];
    selection["residentKey"] = "required".into();
    selection["requireResidentKey"] = true.into();
    Ok(Json(body))
}

pub async fn register_verify(
    State(passkeys): State<Arc<Passkeys>>,
    State(pool): State<PgPool>,
    body: Result<Json<VerifyBody>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let Json(body) = body?;
    let Some(Ceremony::Register(account_id, registration)) =
        passkeys.take(&body.ceremony_id, Instant::now())
    else {
        return Err(ApiError::INVALID_CEREMONY);
    };
    let credential: RegisterPublicKeyCredential =
        serde_json::from_str(&body.response).map_err(rejected)?;
    let passkey = passkeys
        .webauthn
        .finish_passkey_registration(&credential, &registration)
        .map_err(rejected)?;

    let mut tx = pool.begin().await?;
    sqlx::query!("INSERT INTO accounts (id) VALUES ($1)", account_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query!(
        "INSERT INTO passkeys (credential_id, account_id, passkey) VALUES ($1, $2, $3)",
        passkey.cred_id().as_slice(),
        account_id,
        serde_json::to_value(&passkey).expect("passkey serializes")
    )
    .execute(&mut *tx)
    .await?;
    let token = sessions::issue(&mut tx, account_id).await?;
    tx.commit().await?;
    Ok(Json(json!({ "token": token })))
}

pub async fn login_options(State(passkeys): State<Arc<Passkeys>>) -> Result<Json<Value>, ApiError> {
    // No username, no allowCredentials: the phone offers its discoverable passkeys.
    let (rcr, authentication) = passkeys
        .webauthn
        .start_discoverable_authentication()
        .map_err(|e| {
            tracing::error!("start authentication: {e}");
            ApiError::INTERNAL
        })?;
    let id = passkeys.put(Ceremony::Login(authentication), Instant::now());
    Ok(options(id, rcr))
}

pub async fn login_verify(
    State(passkeys): State<Arc<Passkeys>>,
    State(pool): State<PgPool>,
    body: Result<Json<VerifyBody>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let Json(body) = body?;
    let Some(Ceremony::Login(authentication)) = passkeys.take(&body.ceremony_id, Instant::now())
    else {
        return Err(ApiError::INVALID_CEREMONY);
    };
    let credential: PublicKeyCredential = serde_json::from_str(&body.response).map_err(rejected)?;
    let (account_id, credential_id) = passkeys
        .webauthn
        .identify_discoverable_authentication(&credential)
        .map_err(rejected)?;
    let stored = sqlx::query_scalar!(
        "SELECT passkey FROM passkeys WHERE credential_id = $1 AND account_id = $2",
        credential_id,
        account_id
    )
    .fetch_optional(&pool)
    .await?
    .ok_or_else(|| rejected("unknown credential"))?;
    let mut passkey: Passkey = serde_json::from_value(stored).map_err(|e| {
        tracing::error!("stored passkey does not parse: {e}");
        ApiError::INTERNAL
    })?;
    let result = passkeys
        .webauthn
        .finish_discoverable_authentication_v2(&credential, authentication, &passkey)
        .map_err(rejected)?;
    // Counter and backup state; synced passkeys report a counter of 0.
    passkey.update_credential(&result);

    let mut tx = pool.begin().await?;
    sqlx::query!(
        "UPDATE passkeys SET passkey = $1, last_used_at = now() WHERE credential_id = $2",
        serde_json::to_value(&passkey).expect("passkey serializes"),
        credential_id
    )
    .execute(&mut *tx)
    .await?;
    let token = sessions::issue(&mut tx, account_id).await?;
    tx.commit().await?;
    Ok(Json(json!({ "token": token })))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn passkeys() -> Passkeys {
        let origin = Url::parse("android:apk-key-hash:abc").unwrap();
        Passkeys::new("esper.media", &[origin]).unwrap()
    }

    fn login(p: &Passkeys) -> Ceremony {
        Ceremony::Login(p.webauthn.start_discoverable_authentication().unwrap().1)
    }

    #[test]
    fn a_ceremony_is_single_use() {
        let p = passkeys();
        let now = Instant::now();
        let id = p.put(login(&p), now);
        assert!(p.take("unknown", now).is_none());
        assert!(p.take(&id, now).is_some());
        assert!(p.take(&id, now).is_none());
    }

    #[test]
    fn a_ceremony_expires_after_five_minutes_and_is_purged() {
        let p = passkeys();
        let now = Instant::now();
        let fresh = p.put(login(&p), now);
        let old = p.put(login(&p), now);
        assert!(p.take(&old, now + CEREMONY_TTL).is_none());
        let stale = p.put(login(&p), now);
        p.purge(now + CEREMONY_TTL);
        assert!(p.ceremonies.lock().unwrap().is_empty());
        assert!(p.take(&stale, now).is_none());
        assert!(p.take(&fresh, now).is_none());
    }
}
