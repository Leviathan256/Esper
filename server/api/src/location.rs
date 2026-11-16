//! The latest fix per account, in memory only (docs/SERVER.md §7): overwritten
//! by the next fix, dropped after 10 minutes without one, on logout and on
//! restart. Never written to Postgres or logs. This is the player-facing rule
//! in docs/GAME_DESIGN.md ("Why the app asks for location"); change it there first.

use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

use axum::{
    Json,
    extract::{State, rejection::JsonRejection},
    http::StatusCode,
};
use serde::Deserialize;
use webauthn_rs::prelude::Uuid;

use crate::{auth::sessions::Session, error::ApiError};

const TTL: Duration = Duration::from_secs(10 * 60);

/// What `POST /v1/location` and the socket `loc` message carry.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FixBody {
    lat: f64,
    lon: f64,
    accuracy_m: f64,
}

/// `received_at` is server time; client time is never accepted.
#[allow(dead_code)] // lat, lon and accuracy_m are read by presence, a later step
struct Fix {
    lat: f64,
    lon: f64,
    accuracy_m: f64,
    received_at: Instant,
}

#[derive(Default)]
pub struct Positions(Mutex<HashMap<Uuid, Fix>>);

/// Range checks reject NaN and infinities too: `contains` is false for them.
pub fn validate(lat: f64, lon: f64, accuracy_m: f64) -> bool {
    (-90.0..=90.0).contains(&lat)
        && (-180.0..=180.0).contains(&lon)
        && (0.0..=10_000.0).contains(&accuracy_m)
}

impl Positions {
    /// Keeps the fix if it passes `validate`; false means `invalid_fix`.
    pub fn record(&self, account_id: Uuid, body: &FixBody, now: Instant) -> bool {
        let &FixBody {
            lat,
            lon,
            accuracy_m,
        } = body;
        if !validate(lat, lon, accuracy_m) {
            return false;
        }
        let fix = Fix {
            lat,
            lon,
            accuracy_m,
            received_at: now,
        };
        self.0.lock().unwrap().insert(account_id, fix);
        true
    }

    /// Logout.
    pub fn remove(&self, account_id: Uuid) {
        self.0.lock().unwrap().remove(&account_id);
    }

    /// Minute tick: drops every fix older than 10 minutes.
    pub fn purge(&self, now: Instant) {
        let mut map = self.0.lock().unwrap();
        map.retain(|_, fix| now.duration_since(fix.received_at) <= TTL);
    }

    #[cfg(test)]
    pub fn has(&self, account_id: Uuid) -> bool {
        self.0.lock().unwrap().contains_key(&account_id)
    }
}

/// `POST /v1/location`.
pub async fn post_location(
    State(positions): State<Arc<Positions>>,
    session: Session,
    body: Result<Json<FixBody>, JsonRejection>,
) -> Result<StatusCode, ApiError> {
    let Json(body) = body?;
    if !positions.record(session.account_id, &body, Instant::now()) {
        return Err(ApiError::INVALID_FIX);
    }
    Ok(StatusCode::NO_CONTENT)
}

#[cfg(test)]
mod tests {
    use super::*;

    const OK: FixBody = FixBody {
        lat: 51.5,
        lon: -0.12,
        accuracy_m: 5.0,
    };

    #[test]
    fn validate_rejects_out_of_range_nan_and_infinite() {
        assert!(validate(51.5, -0.12, 10.0));
        assert!(validate(-90.0, 180.0, 0.0));
        assert!(validate(90.0, -180.0, 10_000.0));
        for (lat, lon, acc) in [
            (90.1, 0.0, 5.0),
            (-90.1, 0.0, 5.0),
            (0.0, 180.5, 5.0),
            (0.0, -180.5, 5.0),
            (0.0, 0.0, -1.0),
            (0.0, 0.0, 10_000.1),
            (f64::NAN, 0.0, 5.0),
            (0.0, f64::NAN, 5.0),
            (0.0, 0.0, f64::NAN),
            (f64::INFINITY, 0.0, 5.0),
            (0.0, 0.0, f64::INFINITY),
        ] {
            assert!(!validate(lat, lon, acc), "{lat} {lon} {acc}");
        }
    }

    #[test]
    fn record_keeps_only_a_valid_fix() {
        let positions = Positions::default();
        let id = Uuid::new_v4();
        let bad = FixBody { lat: 91.0, ..OK };
        assert!(!positions.record(id, &bad, Instant::now()));
        assert!(!positions.has(id));
        assert!(positions.record(id, &OK, Instant::now()));
        assert!(positions.has(id));
    }

    #[test]
    fn purge_drops_a_fix_older_than_ten_minutes_and_keeps_a_fresh_one() {
        let positions = Positions::default();
        let t0 = Instant::now();
        let (stale, fresh) = (Uuid::new_v4(), Uuid::new_v4());
        positions.record(stale, &OK, t0);
        positions.record(fresh, &OK, t0 + Duration::from_secs(2));
        positions.purge(t0 + Duration::from_secs(601));
        assert!(!positions.has(stale));
        assert!(positions.has(fresh));
    }

    #[test]
    fn remove_drops_the_fix() {
        let positions = Positions::default();
        let id = Uuid::new_v4();
        positions.record(id, &OK, Instant::now());
        positions.remove(id);
        assert!(!positions.has(id));
    }
}
