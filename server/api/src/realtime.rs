//! `/v1/ws` skeleton (docs/SERVER.md §7). The app does not open it yet; later
//! steps add message types to `reply`. No connection registry yet.

use std::{sync::Arc, time::Duration};

use sqlx::PgPool;
use tokio::sync::Semaphore;

use axum::{
    extract::{
        State, WebSocketUpgrade,
        ws::{Message, WebSocket, rejection::WebSocketUpgradeRejection},
    },
    response::Response,
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tokio::time::Instant;
use webauthn_rs::prelude::Uuid;

use crate::{
    MAX_BODY,
    auth::sessions::Session,
    error::ApiError,
    location::{FixBody, Positions},
};

/// Open sockets across all accounts; far above a handful of players, and bounds memory.
pub const MAX_SOCKETS: usize = 1000;
const PING_EVERY: Duration = Duration::from_secs(30);
/// Closed when the client sends nothing (pongs included) for this long.
const TIMEOUT: Duration = Duration::from_secs(60);

/// Every socket message, both directions: type, optional correlation id, payload.
#[derive(Serialize, Deserialize)]
struct Envelope {
    t: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    id: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    d: Option<Value>,
}

impl Envelope {
    fn message(t: &str, id: Option<i64>, d: Option<Value>) -> Message {
        let env = Self { t: t.into(), id, d };
        Message::text(serde_json::to_string(&env).expect("envelope serializes"))
    }

    fn err(id: Option<i64>, code: &str) -> Message {
        Self::message("err", id, Some(json!({"error": code})))
    }
}

/// `GET /v1/ws`: the bearer is checked before the upgrade, so no token means 401.
pub async fn ws(
    session: Session,
    State(pool): State<PgPool>,
    State(positions): State<Arc<Positions>>,
    State(sockets): State<Arc<Semaphore>>,
    upgrade: Result<WebSocketUpgrade, WebSocketUpgradeRejection>,
) -> Result<Response, ApiError> {
    let upgrade = upgrade.map_err(|_| ApiError::BAD_REQUEST)?;
    let permit = sockets
        .try_acquire_owned()
        .map_err(|_| ApiError::RATE_LIMITED)?;
    // tungstenite's defaults are 64 MB per message and a 128 KB read buffer per socket.
    Ok(upgrade
        .read_buffer_size(8 * 1024)
        .max_message_size(MAX_BODY)
        .max_frame_size(MAX_BODY)
        .on_upgrade(move |socket| async move {
            run(socket, session, pool, positions).await;
            drop(permit);
        }))
}

async fn run(mut socket: WebSocket, session: Session, pool: PgPool, positions: Arc<Positions>) {
    let account_id = session.account_id;
    if socket
        .send(Envelope::message("hello", None, None))
        .await
        .is_err()
    {
        return;
    }
    let mut ping = tokio::time::interval_at(Instant::now() + PING_EVERY, PING_EVERY);
    let mut deadline = Instant::now() + TIMEOUT;
    loop {
        let out = tokio::select! {
            msg = socket.recv() => {
                // None or an error: the connection is over.
                let Some(Ok(msg)) = msg else { return };
                deadline = Instant::now() + TIMEOUT;
                // Pongs, binary, close: tungstenite answers pings and closes itself, then recv ends.
                let Message::Text(text) = msg else { continue };
                // After logout or expiry the socket must not store a position again.
                if !session.is_live(&pool).await {
                    return;
                }
                reply(text.as_str(), account_id, &positions)
            }
            _ = ping.tick() => Message::Ping(Default::default()),
            _ = tokio::time::sleep_until(deadline) => return,
        };
        if socket.send(out).await.is_err() {
            return;
        }
    }
}

fn reply(text: &str, account_id: Uuid, positions: &Positions) -> Message {
    let Ok(env) = serde_json::from_str::<Envelope>(text) else {
        return Envelope::err(None, "bad_request");
    };
    match env.t.as_str() {
        "loc" => {
            let fix = env
                .d
                .and_then(|d| serde_json::from_value::<FixBody>(d).ok());
            match fix {
                Some(fix) if positions.record(account_id, &fix, std::time::Instant::now()) => {
                    Envelope::message("ack", env.id, None)
                }
                _ => Envelope::err(env.id, "invalid_fix"),
            }
        }
        _ => Envelope::err(env.id, "unknown_type"),
    }
}
