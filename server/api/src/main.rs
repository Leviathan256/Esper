//! Esper server (docs/SERVER.md): loads content and migrates the database on
//! start, then serves the API. Invalid content stops startup (§8).

use std::{collections::BTreeMap, path::Path, time::Duration};

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
    let content_dir = std::env::var("ESPER_CONTENT_DIR").unwrap_or_else(|_| "content".into());

    let content = load_content(Path::new(&content_dir))?;
    tracing::info!(
        "content: {} jobs, {} monsters",
        content.jobs.len(),
        content.monsters.len()
    );

    let pool = pool_options().connect(&db_url).await?;
    MIGRATOR.run(&pool).await?;

    let listener = tokio::net::TcpListener::bind(&bind).await?;
    tracing::info!("listening on {bind}");
    axum::serve(listener, app(pool))
        .with_graceful_shutdown(shutdown())
        .await?;
    Ok(())
}

fn load_content(dir: &Path) -> Result<rules::content::Catalog, Box<dyn std::error::Error>> {
    let read = |sub: &str| -> Result<BTreeMap<String, String>, Box<dyn std::error::Error>> {
        let dir = dir.join(sub);
        let mut texts = BTreeMap::new();
        for entry in std::fs::read_dir(&dir).map_err(|e| format!("{}: {e}", dir.display()))? {
            let path = entry?.path();
            if path.extension().is_some_and(|x| x == "json") {
                let name = path
                    .file_name()
                    .unwrap_or_default()
                    .to_string_lossy()
                    .into_owned();
                texts.insert(name, std::fs::read_to_string(&path)?);
            }
        }
        if texts.is_empty() {
            return Err(format!("no *.json files in {}", dir.display()).into());
        }
        Ok(texts)
    };
    Ok(rules::content::load(&read("jobs")?, &read("monsters")?)?)
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

    const CONTENT: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../../content");

    #[test]
    fn shipped_content_loads() {
        let c = load_content(Path::new(CONTENT)).unwrap();
        assert_eq!((c.jobs.len(), c.monsters.len()), (3, 3));
    }

    #[test]
    fn a_broken_or_missing_content_file_stops_startup() {
        let dir = std::env::temp_dir().join(format!("esper-content-{}", std::process::id()));
        for sub in ["jobs", "monsters"] {
            std::fs::create_dir_all(dir.join(sub)).unwrap();
            for f in std::fs::read_dir(Path::new(CONTENT).join(sub)).unwrap() {
                let f = f.unwrap().path();
                std::fs::copy(&f, dir.join(sub).join(f.file_name().unwrap())).unwrap();
            }
        }
        assert!(load_content(&dir).is_ok());

        let wolf = dir.join("monsters/wolf.json");
        let text = std::fs::read_to_string(&wolf).unwrap();
        std::fs::write(&wolf, text.replacen("\"maxHp\"", "\"maxHP\"", 1)).unwrap();
        let err = load_content(&dir).unwrap_err().to_string();
        assert!(err.contains("wolf.json"), "{err}");

        std::fs::remove_dir_all(dir.join("monsters")).unwrap();
        assert!(load_content(&dir).is_err());
        std::fs::remove_dir_all(&dir).unwrap();
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
