//! Opaque bearer sessions (docs/SERVER.md §6). The raw token goes to the app
//! once and is never stored or logged: the table holds only its SHA-256.
//!
//! shortcut: no token rotation; add it if a token ever leaks in practice.

use std::sync::Arc;

use axum::{
    extract::{FromRef, FromRequestParts, State},
    http::{StatusCode, header::AUTHORIZATION, request::Parts},
};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use sha2::{Digest, Sha256};
use sqlx::{PgConnection, PgPool};
use webauthn_rs::prelude::Uuid;

use crate::{error::ApiError, location::Positions};

/// Keep only the newest sessions per account, so scripted sign-ins cannot fill the disk.
const MAX_PER_ACCOUNT: i64 = 10;

fn hash(token: &str) -> Vec<u8> {
    Sha256::digest(token.as_bytes()).to_vec()
}

/// Inside the caller's transaction, so account + passkey + session commit together.
pub async fn issue(c: &mut PgConnection, account_id: Uuid) -> Result<String, sqlx::Error> {
    let mut raw = [0u8; 32];
    getrandom::fill(&mut raw).expect("OS random source");
    let token = URL_SAFE_NO_PAD.encode(raw);
    sqlx::query!(
        "INSERT INTO sessions (token_hash, account_id, expires_at) VALUES ($1, $2, now() + interval '90 days')",
        hash(&token),
        account_id
    )
    .execute(&mut *c)
    .await?;
    sqlx::query!(
        "DELETE FROM sessions WHERE account_id = $1 AND token_hash NOT IN
           (SELECT token_hash FROM sessions WHERE account_id = $1 ORDER BY created_at DESC LIMIT $2)",
        account_id,
        MAX_PER_ACCOUNT
    )
    .execute(&mut *c)
    .await?;
    Ok(token)
}

/// The account behind an unexpired session. Slides the expiry at most once a day,
/// so most requests do not write.
async fn validate(pool: &PgPool, token_hash: &[u8]) -> Result<Option<Uuid>, sqlx::Error> {
    let Some(row) = sqlx::query!(
        r#"SELECT account_id, last_used_at < now() - interval '1 day' AS "stale!"
           FROM sessions WHERE token_hash = $1 AND expires_at > now()"#,
        token_hash
    )
    .fetch_optional(pool)
    .await?
    else {
        return Ok(None);
    };
    if row.stale {
        sqlx::query!(
            "UPDATE sessions SET last_used_at = now(), expires_at = now() + interval '90 days' WHERE token_hash = $1",
            token_hash
        )
        .execute(pool)
        .await?;
    }
    Ok(Some(row.account_id))
}

pub async fn purge_expired(pool: &PgPool) -> Result<(), sqlx::Error> {
    sqlx::query!("DELETE FROM sessions WHERE expires_at <= now()")
        .execute(pool)
        .await?;
    Ok(())
}

/// Bearer extractor: a route that takes a `Session` answers 401 to a missing,
/// unknown or expired token. Keeps the hash so logout deletes exactly this session.
pub struct Session {
    pub account_id: Uuid,
    token_hash: Vec<u8>,
}

impl<S> FromRequestParts<S> for Session
where
    PgPool: FromRef<S>,
    S: Send + Sync,
{
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, state: &S) -> Result<Self, ApiError> {
        let token = parts
            .headers
            .get(AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.strip_prefix("Bearer "))
            .ok_or(ApiError::UNAUTHORIZED)?;
        let token_hash = hash(token);
        let account_id = validate(&PgPool::from_ref(state), &token_hash)
            .await?
            .ok_or(ApiError::UNAUTHORIZED)?;
        Ok(Self {
            account_id,
            token_hash,
        })
    }
}

impl Session {
    /// False once this session is logged out or expired, or the database is unreachable.
    pub async fn is_live(&self, pool: &PgPool) -> bool {
        matches!(validate(pool, &self.token_hash).await, Ok(Some(_)))
    }
}

/// `POST /v1/auth/logout`: deletes this session only, and the account's position.
pub async fn logout(
    State(pool): State<PgPool>,
    State(positions): State<Arc<Positions>>,
    session: Session,
) -> Result<StatusCode, ApiError> {
    sqlx::query!(
        "DELETE FROM sessions WHERE token_hash = $1",
        session.token_hash
    )
    .execute(&pool)
    .await?;
    positions.remove(session.account_id);
    Ok(StatusCode::NO_CONTENT)
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    pub async fn test_pool() -> Option<PgPool> {
        let Ok(url) = std::env::var("ESPER_TEST_DB_URL") else {
            eprintln!("skipped: ESPER_TEST_DB_URL is not set");
            return None;
        };
        let pool = PgPool::connect(&url).await.unwrap();
        crate::MIGRATOR.run(&pool).await.unwrap();
        Some(pool)
    }

    pub async fn new_account(pool: &PgPool) -> Uuid {
        let id = Uuid::new_v4();
        sqlx::query!("INSERT INTO accounts (id) VALUES ($1)", id)
            .execute(pool)
            .await
            .unwrap();
        id
    }

    pub async fn new_session(pool: &PgPool, account_id: Uuid) -> String {
        issue(&mut pool.acquire().await.unwrap(), account_id)
            .await
            .unwrap()
    }

    async fn expires_in_days(pool: &PgPool, token: &str) -> f64 {
        sqlx::query_scalar!(
            r#"SELECT extract(epoch FROM expires_at - now())::float8 / 86400 AS "d!" FROM sessions WHERE token_hash = $1"#,
            hash(token)
        )
        .fetch_one(pool)
        .await
        .unwrap()
    }

    #[tokio::test]
    async fn only_the_hash_is_stored_and_validate_finds_the_account() {
        let Some(pool) = test_pool().await else {
            return;
        };
        let account = new_account(&pool).await;
        let token = new_session(&pool, account).await;
        assert_eq!(URL_SAFE_NO_PAD.decode(&token).unwrap().len(), 32);
        let stored = sqlx::query_scalar!(
            "SELECT token_hash FROM sessions WHERE account_id = $1",
            account
        )
        .fetch_one(&pool)
        .await
        .unwrap();
        assert_eq!(stored, hash(&token));
        assert_eq!(validate(&pool, &hash(&token)).await.unwrap(), Some(account));
        assert_eq!(validate(&pool, &hash("nope")).await.unwrap(), None);
        assert!((expires_in_days(&pool, &token).await - 90.0).abs() < 0.01);
    }

    #[tokio::test]
    async fn validate_slides_a_day_old_session_and_rejects_an_expired_one() {
        let Some(pool) = test_pool().await else {
            return;
        };
        let account = new_account(&pool).await;
        let token = new_session(&pool, account).await;
        sqlx::query!(
            "UPDATE sessions SET last_used_at = now() - interval '2 days', expires_at = now() + interval '1 hour' WHERE token_hash = $1",
            hash(&token)
        )
        .execute(&pool)
        .await
        .unwrap();
        assert_eq!(validate(&pool, &hash(&token)).await.unwrap(), Some(account));
        assert!((expires_in_days(&pool, &token).await - 90.0).abs() < 0.01);

        sqlx::query!(
            "UPDATE sessions SET expires_at = now() - interval '1 second' WHERE token_hash = $1",
            hash(&token)
        )
        .execute(&pool)
        .await
        .unwrap();
        assert_eq!(validate(&pool, &hash(&token)).await.unwrap(), None);
        purge_expired(&pool).await.unwrap();
        let left = sqlx::query_scalar!(
            r#"SELECT count(*) AS "n!" FROM sessions WHERE account_id = $1"#,
            account
        )
        .fetch_one(&pool)
        .await
        .unwrap();
        assert_eq!(left, 0);
    }

    #[tokio::test]
    async fn an_account_keeps_only_its_newest_sessions() {
        let Some(pool) = test_pool().await else {
            return;
        };
        let account = new_account(&pool).await;
        let first = new_session(&pool, account).await;
        for _ in 0..MAX_PER_ACCOUNT {
            new_session(&pool, account).await;
        }
        let n = sqlx::query_scalar!(
            r#"SELECT count(*) AS "n!" FROM sessions WHERE account_id = $1"#,
            account
        )
        .fetch_one(&pool)
        .await
        .unwrap();
        assert_eq!(n, MAX_PER_ACCOUNT);
        assert_eq!(validate(&pool, &hash(&first)).await.unwrap(), None);
    }
}
