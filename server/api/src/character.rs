//! `GET`, `POST` and `PUT /v1/character` (docs/SERVER.md §5). One character
//! per account; `state` holds `save_codec::encode` output in a jsonb column.
//! The checks themselves live in `rules::character`.

use std::sync::Arc;

use axum::{
    Json,
    extract::{
        State,
        rejection::{JsonRejection, StringRejection},
    },
    http::{StatusCode, header::CONTENT_TYPE},
    response::{IntoResponse, Response},
};
use rules::{
    character::{self, CharacterState, save_codec},
    content::Catalog,
};
use serde::Deserialize;
use sqlx::PgPool;

use crate::{auth::sessions::Session, error::ApiError};

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateBody {
    name: String,
    starting_job_id: String,
}

fn state_json(status: StatusCode, text: String) -> Response {
    (status, [(CONTENT_TYPE, "application/json")], text).into_response()
}

/// A stored row the codec cannot read is a server fault, not the caller's.
fn decode_stored(text: &str) -> Result<CharacterState, ApiError> {
    save_codec::decode(text).map_err(|e| {
        // The category only: the error text can quote the character's data.
        tracing::error!("stored character does not decode: {:?}", e.classify());
        ApiError::INTERNAL
    })
}

/// Re-encoded rather than returned raw: jsonb reorders keys, and decode runs migrations.
pub async fn get_character(
    State(pool): State<PgPool>,
    session: Session,
) -> Result<Response, ApiError> {
    let stored = sqlx::query_scalar!(
        r#"SELECT state::text AS "state!" FROM characters WHERE account_id = $1"#,
        session.account_id
    )
    .fetch_optional(&pool)
    .await?
    .ok_or(ApiError::NO_CHARACTER)?;
    let state = decode_stored(&stored)?;
    Ok(state_json(StatusCode::OK, save_codec::encode(&state)))
}

pub async fn post_character(
    State(pool): State<PgPool>,
    State(catalog): State<Arc<Catalog>>,
    session: Session,
    body: Result<Json<CreateBody>, JsonRejection>,
) -> Result<Response, ApiError> {
    let Json(body) = body?;
    let text = save_codec::encode(&character::create(
        &catalog,
        &body.name,
        &body.starting_job_id,
    )?);
    let inserted = sqlx::query!(
        "INSERT INTO characters (account_id, state) VALUES ($1, $2::text::jsonb) ON CONFLICT (account_id) DO NOTHING",
        session.account_id,
        text
    )
    .execute(&pool)
    .await?
    .rows_affected();
    if inserted == 0 {
        return Err(ApiError::CHARACTER_EXISTS);
    }
    Ok(state_json(StatusCode::CREATED, text))
}

pub async fn put_character(
    State(pool): State<PgPool>,
    State(catalog): State<Arc<Catalog>>,
    session: Session,
    body: Result<String, StringRejection>,
) -> Result<StatusCode, ApiError> {
    let text = body.map_err(|_| ApiError::BAD_REQUEST)?;
    let mut tx = pool.begin().await?;
    // FOR UPDATE: two racing PUTs cannot both pass the level check against the same row.
    let stored = sqlx::query_scalar!(
        r#"SELECT state::text AS "state!" FROM characters WHERE account_id = $1 FOR UPDATE"#,
        session.account_id
    )
    .fetch_optional(&mut *tx)
    .await?
    .map(|s| decode_stored(&s))
    .transpose()?;
    let state = character::update(&catalog, &text, stored.as_ref())?;
    sqlx::query!(
        "INSERT INTO characters (account_id, state) VALUES ($1, $2::text::jsonb)
         ON CONFLICT (account_id) DO UPDATE SET state = EXCLUDED.state, updated_at = now()",
        session.account_id,
        save_codec::encode(&state)
    )
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}
