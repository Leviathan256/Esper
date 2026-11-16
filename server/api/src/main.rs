//! Esper server (docs/SERVER.md): migrates the database on start, then serves the API.

use std::time::Duration;

use axum::{
    Json, Router,
    extract::State,
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::get,
};
use sqlx::{PgPool, migrate::Migrator, postgres::PgPoolOptions};
use tracing_subscriber::EnvFilter;

static MIGRATOR: Migrator = sqlx::migrate!("../migrations");

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();
    let bind = std::env::var("ESPER_BIND").unwrap_or_else(|_| "0.0.0.0:8080".into());
    let db_url = std::env::var("ESPER_DB_URL").map_err(|_| "ESPER_DB_URL is required")?;

    let pool = pool_options().connect(&db_url).await?;
    MIGRATOR.run(&pool).await?;

    let listener = tokio::net::TcpListener::bind(&bind).await?;
    tracing::info!("listening on {bind}");
    axum::serve(listener, app(pool))
        .with_graceful_shutdown(shutdown())
        .await?;
    Ok(())
}

// A probe must not hang for sqlx's 30 s default while the database is down.
fn pool_options() -> PgPoolOptions {
    PgPoolOptions::new().acquire_timeout(Duration::from_secs(3))
}

fn app(pool: PgPool) -> Router {
    Router::new()
        .route("/healthz", get(healthz))
        .with_state(pool)
}

async fn healthz(State(pool): State<PgPool>) -> Response {
    match sqlx::query_scalar!(r#"SELECT 1 AS "one!""#)
        .fetch_one(&pool)
        .await
    {
        Ok(_) => "ok".into_response(),
        Err(e) => {
            tracing::warn!("healthz: database check failed: {e}");
            let body =
                serde_json::json!({"error": "unavailable", "message": "Database is unreachable."});
            (StatusCode::SERVICE_UNAVAILABLE, Json(body)).into_response()
        }
    }
}

// The binary is PID 1 in the container, where SIGTERM without a handler is ignored.
async fn shutdown() {
    let mut term = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        .expect("SIGTERM handler");
    tokio::select! {
        _ = tokio::signal::ctrl_c() => {}
        _ = term.recv() => {}
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::{Body, to_bytes};
    use axum::http::Request;
    use tower::ServiceExt;

    async fn get_healthz(pool: PgPool) -> (StatusCode, String) {
        let req = Request::get("/healthz").body(Body::empty()).unwrap();
        let res = app(pool).oneshot(req).await.unwrap();
        let status = res.status();
        let body = to_bytes(res.into_body(), 64 * 1024).await.unwrap();
        (status, String::from_utf8(body.to_vec()).unwrap())
    }

    #[tokio::test]
    async fn healthz_is_503_when_the_database_is_down() {
        // Nothing listens on port 1, so every connection attempt is refused.
        let pool = pool_options()
            .connect_lazy("postgres://esper@127.0.0.1:1/esper")
            .unwrap();
        let (status, body) = get_healthz(pool).await;
        assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE);
        let body: serde_json::Value = serde_json::from_str(&body).unwrap();
        assert_eq!(body["error"], "unavailable");
    }

    #[tokio::test]
    async fn healthz_is_ok_after_migrating() {
        let Ok(url) = std::env::var("ESPER_TEST_DB_URL") else {
            eprintln!("skipped: ESPER_TEST_DB_URL is not set");
            return;
        };
        let pool = pool_options().connect(&url).await.unwrap();
        MIGRATOR.run(&pool).await.unwrap();
        assert_eq!(get_healthz(pool).await, (StatusCode::OK, "ok".to_string()));
    }
}
