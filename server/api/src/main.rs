//! Esper server (docs/SERVER.md): loads content and migrates the database on
//! start, then serves the API. Invalid content or an empty `ESPER_RP_ORIGINS`
//! stops startup (§6, §8).

mod auth;
mod config;
mod error;
mod location;
mod realtime;

use std::{
    collections::BTreeMap,
    net::SocketAddr,
    path::{Path, PathBuf},
    sync::Arc,
    time::{Duration, Instant},
};

use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, FromRef, Request, State},
    http::{StatusCode, header::CONTENT_TYPE},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use governor::middleware::NoOpMiddleware;
use sqlx::{PgPool, migrate::Migrator, postgres::PgPoolOptions};
use tower_governor::{
    GovernorLayer,
    governor::{GovernorConfig, GovernorConfigBuilder},
};
use tracing_subscriber::EnvFilter;

use auth::{ClientIp, passkeys, passkeys::Passkeys, sessions};
use error::ApiError;
use location::Positions;

static MIGRATOR: Migrator = sqlx::migrate!("../migrations");

const MAX_BODY: usize = 64 * 1024;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();
    let config = config::Config::from_env()?;

    let content = load_content(Path::new(&config.content_dir))?;
    tracing::info!(
        "content: {} jobs, {} monsters; rp {}",
        content.jobs.len(),
        content.monsters.len(),
        config.rp_id
    );

    let pool = pool_options().connect(&config.db_url).await?;
    MIGRATOR.run(&pool).await?;

    let passkeys = Passkeys::new(&config.rp_id, &config.rp_origins)?;
    let state = AppState::new(pool, passkeys, config.assetlinks_file);
    tokio::spawn(minute_tick(state.clone()));

    let listener = tokio::net::TcpListener::bind(&config.bind).await?;
    tracing::info!("listening on {}", config.bind);
    // ConnectInfo gives the rate limiter the peer address when CF-Connecting-IP is absent.
    axum::serve(
        listener,
        app(state).into_make_service_with_connect_info::<SocketAddr>(),
    )
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

#[derive(Clone)]
struct AppState {
    pool: PgPool,
    passkeys: Arc<Passkeys>,
    assetlinks_file: Arc<PathBuf>,
    positions: Arc<Positions>,
    auth_limit: Arc<GovernorConfig<ClientIp, NoOpMiddleware>>,
}

impl AppState {
    fn new(pool: PgPool, passkeys: Passkeys, assetlinks_file: PathBuf) -> Self {
        // 20 per minute per client IP: a burst of 20, one back every 3 s.
        let auth_limit = GovernorConfigBuilder::default()
            .per_millisecond(3000)
            .burst_size(20)
            .key_extractor(ClientIp)
            .finish()
            .expect("non-zero rate limit");
        Self {
            pool,
            passkeys: Arc::new(passkeys),
            assetlinks_file: Arc::new(assetlinks_file),
            positions: Arc::default(),
            auth_limit: Arc::new(auth_limit),
        }
    }
}

impl FromRef<AppState> for PgPool {
    fn from_ref(s: &AppState) -> Self {
        s.pool.clone()
    }
}

impl FromRef<AppState> for Arc<Passkeys> {
    fn from_ref(s: &AppState) -> Self {
        s.passkeys.clone()
    }
}

impl FromRef<AppState> for Arc<Positions> {
    fn from_ref(s: &AppState) -> Self {
        s.positions.clone()
    }
}

fn app(state: AppState) -> Router {
    let auth = Router::new()
        .route(
            "/v1/auth/register/options",
            post(passkeys::register_options),
        )
        .route("/v1/auth/register/verify", post(passkeys::register_verify))
        .route("/v1/auth/login/options", post(passkeys::login_options))
        .route("/v1/auth/login/verify", post(passkeys::login_verify))
        .route("/v1/auth/logout", post(sessions::logout))
        // route_layer, not layer: layer would also wrap the fallback, so unknown paths would spend the sign-in budget.
        .route_layer(
            GovernorLayer::new(state.auth_limit.clone())
                .error_handler(|_| ApiError::RATE_LIMITED.into_response()),
        );
    Router::new()
        .route("/healthz", get(healthz))
        .route("/.well-known/assetlinks.json", get(assetlinks))
        .route("/v1/location", post(location::post_location))
        .route("/v1/ws", get(realtime::ws))
        .merge(auth)
        .layer(DefaultBodyLimit::max(MAX_BODY))
        .layer(middleware::from_fn(log_request))
        .with_state(state)
}

/// Method, path, status, latency only: never bodies, headers or query strings,
/// so tokens and positions cannot reach the log.
async fn log_request(req: Request, next: Next) -> Response {
    let (method, path, start) = (
        req.method().clone(),
        req.uri().path().to_owned(),
        Instant::now(),
    );
    let res = next.run(req).await;
    tracing::info!(
        "{method} {path} {} {:?}",
        res.status().as_u16(),
        start.elapsed()
    );
    res
}

/// Read per request, so an updated ConfigMap is served without a restart.
async fn assetlinks(State(state): State<AppState>) -> Response {
    match tokio::fs::read(&*state.assetlinks_file).await {
        Ok(bytes) => ([(CONTENT_TYPE, "application/json")], bytes).into_response(),
        Err(e) => {
            tracing::warn!("{}: {e}", state.assetlinks_file.display());
            ApiError::NOT_FOUND.into_response()
        }
    }
}

/// Expired ceremonies, stale positions, expired sessions, idle rate-limit keys.
async fn minute_tick(state: AppState) {
    let mut tick = tokio::time::interval(Duration::from_secs(60));
    loop {
        tick.tick().await;
        state.passkeys.purge(Instant::now());
        state.positions.purge(Instant::now());
        state.auth_limit.limiter().retain_recent();
        if let Err(e) = sessions::purge_expired(&state.pool).await {
            tracing::warn!("session purge failed: {e}");
        }
    }
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
    use serde_json::{Value, json};
    use tower::ServiceExt;

    fn test_state(pool: PgPool) -> AppState {
        let origin = webauthn_rs::prelude::Url::parse("android:apk-key-hash:abc").unwrap();
        let passkeys = Passkeys::new("esper.media", &[origin]).unwrap();
        AppState::new(pool, passkeys, "/nonexistent".into())
    }

    fn test_app(pool: PgPool, assetlinks_file: &str) -> Router {
        let mut state = test_state(pool);
        state.assetlinks_file = Arc::new(assetlinks_file.into());
        app(state)
    }

    /// For routes that never reach the database.
    fn dead_pool() -> PgPool {
        // Nothing listens on port 1, so every connection attempt is refused.
        pool_options()
            .connect_lazy("postgres://esper@127.0.0.1:1/esper")
            .unwrap()
    }

    async fn send(app: &Router, req: Request<Body>) -> (StatusCode, String) {
        let res = app.clone().oneshot(req).await.unwrap();
        let status = res.status();
        let body = to_bytes(res.into_body(), 64 * 1024).await.unwrap();
        (status, String::from_utf8(body.to_vec()).unwrap())
    }

    async fn post_json(app: &Router, path: &str, body: Value) -> (StatusCode, Value) {
        let req = Request::post(path)
            .header(CONTENT_TYPE, "application/json")
            .body(Body::from(body.to_string()))
            .unwrap();
        let (status, body) = send(app, req).await;
        (status, serde_json::from_str(&body).unwrap_or(Value::Null))
    }

    async fn post_location(app: &Router, token: Option<&str>, body: &str) -> (StatusCode, String) {
        let mut req = Request::post("/v1/location").header(CONTENT_TYPE, "application/json");
        if let Some(t) = token {
            req = req.header("Authorization", format!("Bearer {t}"));
        }
        send(app, req.body(Body::from(body.to_owned())).unwrap()).await
    }

    async fn logout(app: &Router, token: Option<&str>) -> (StatusCode, String) {
        let mut req = Request::post("/v1/auth/logout");
        if let Some(t) = token {
            req = req.header("Authorization", format!("Bearer {t}"));
        }
        send(app, req.body(Body::empty()).unwrap()).await
    }

    async fn get_healthz(pool: PgPool) -> (StatusCode, String) {
        let req = Request::get("/healthz").body(Body::empty()).unwrap();
        send(&test_app(pool, "/nonexistent"), req).await
    }

    const CONTENT: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../../content");

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
        let (status, body) = get_healthz(dead_pool()).await;
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

    #[tokio::test]
    async fn register_options_ask_for_a_discoverable_passkey_on_esper_media() {
        let app = test_app(dead_pool(), "/nonexistent");
        let (status, body) = post_json(&app, "/v1/auth/register/options", json!({})).await;
        assert_eq!(status, StatusCode::OK);
        assert!(body["ceremonyId"].as_str().is_some_and(|id| id.len() == 22));
        let pk = &body["publicKey"];
        assert_eq!(pk["rp"]["id"], "esper.media");
        assert_eq!(pk["authenticatorSelection"]["residentKey"], "required");
        assert_eq!(pk["authenticatorSelection"]["requireResidentKey"], true);
        assert_eq!(pk["authenticatorSelection"]["userVerification"], "required");
        assert!(pk["user"]["name"].as_str().unwrap().starts_with("esper-"));
        assert!(pk["challenge"].is_string());
    }

    #[tokio::test]
    async fn login_options_name_no_credentials() {
        let app = test_app(dead_pool(), "/nonexistent");
        let (status, body) = post_json(&app, "/v1/auth/login/options", json!({})).await;
        assert_eq!(status, StatusCode::OK);
        let pk = &body["publicKey"];
        assert_eq!(pk["rpId"], "esper.media");
        assert_eq!(pk["userVerification"], "required");
        assert!(pk["challenge"].is_string());
        assert!(pk["allowCredentials"].as_array().is_none_or(Vec::is_empty));
    }

    #[tokio::test]
    async fn an_unknown_reused_or_mismatched_ceremony_is_invalid_ceremony() {
        let app = test_app(dead_pool(), "/nonexistent");
        let verify = |path: &'static str, id: Value| {
            let app = app.clone();
            async move {
                let (status, body) =
                    post_json(&app, path, json!({"ceremonyId": id, "response": "{}"})).await;
                (status, body["error"].as_str().unwrap().to_owned())
            }
        };
        let invalid = (StatusCode::BAD_REQUEST, "invalid_ceremony".to_owned());
        let rejected = (StatusCode::BAD_REQUEST, "passkey_rejected".to_owned());
        for path in ["/v1/auth/register/verify", "/v1/auth/login/verify"] {
            assert_eq!(verify(path, json!("unknown")).await, invalid);
        }
        for kind in ["register", "login"] {
            let options = format!("/v1/auth/{kind}/options");
            let (_, body) = post_json(&app, &options, json!({})).await;
            let id = body["ceremonyId"].clone();
            let path = if kind == "register" {
                "/v1/auth/register/verify"
            } else {
                "/v1/auth/login/verify"
            };
            // A bad response still uses the ceremony up.
            assert_eq!(verify(path, id.clone()).await, rejected);
            assert_eq!(verify(path, id).await, invalid);
        }
        let (_, body) = post_json(&app, "/v1/auth/login/options", json!({})).await;
        assert_eq!(
            verify("/v1/auth/register/verify", body["ceremonyId"].clone()).await,
            invalid
        );
    }

    #[tokio::test]
    async fn a_malformed_or_oversized_body_is_bad_request() {
        let app = test_app(dead_pool(), "/nonexistent");
        let big = "x".repeat(MAX_BODY + 1);
        for body in [
            "{".to_owned(),
            r#"{"ceremonyId": "x"}"#.to_owned(),
            json!({"ceremonyId": "x", "response": big}).to_string(),
        ] {
            let req = Request::post("/v1/auth/login/verify")
                .header(CONTENT_TYPE, "application/json")
                .body(Body::from(body))
                .unwrap();
            let (status, body) = send(&app, req).await;
            assert_eq!(status, StatusCode::BAD_REQUEST);
            assert!(body.contains(r#""error":"bad_request""#), "{body}");
        }
    }

    #[tokio::test]
    async fn auth_routes_allow_20_a_minute_per_cf_connecting_ip() {
        let app = test_app(dead_pool(), "/nonexistent");
        let options = |ip: &'static str| {
            let app = app.clone();
            async move {
                let req = Request::post("/v1/auth/login/options")
                    .header("CF-Connecting-IP", ip)
                    .body(Body::empty())
                    .unwrap();
                send(&app, req).await
            }
        };
        for _ in 0..20 {
            assert_eq!(options("203.0.113.1").await.0, StatusCode::OK);
        }
        let (status, body) = options("203.0.113.1").await;
        assert_eq!(status, StatusCode::TOO_MANY_REQUESTS);
        assert!(body.contains(r#""error":"rate_limited""#), "{body}");
        assert_eq!(options("203.0.113.2").await.0, StatusCode::OK);
        // Other routes, unknown paths included, are not limited, even for the drained IP.
        // Unknown path first: /healthz on the dead pool waits long enough for a token to refill.
        for (path, want) in [
            ("/nope", StatusCode::NOT_FOUND),
            ("/healthz", StatusCode::SERVICE_UNAVAILABLE),
        ] {
            let req = Request::get(path)
                .header("CF-Connecting-IP", "203.0.113.1")
                .body(Body::empty())
                .unwrap();
            assert_eq!(send(&app, req).await.0, want, "{path}");
        }
    }

    #[tokio::test]
    async fn assetlinks_serves_the_configured_file() {
        let path =
            std::env::temp_dir().join(format!("esper-assetlinks-{}.json", std::process::id()));
        std::fs::write(&path, r#"[{"relation":[]}]"#).unwrap();
        let app = test_app(dead_pool(), path.to_str().unwrap());
        let get = || {
            Request::get("/.well-known/assetlinks.json")
                .body(Body::empty())
                .unwrap()
        };
        let res = app.clone().oneshot(get()).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        assert_eq!(res.headers()[CONTENT_TYPE], "application/json");
        assert_eq!(send(&app, get()).await.1, r#"[{"relation":[]}]"#);
        std::fs::remove_file(&path).unwrap();
        assert_eq!(send(&app, get()).await.0, StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn logout_needs_a_live_session_and_ends_only_that_one() {
        let Some(pool) = sessions::tests::test_pool().await else {
            return;
        };
        let app = test_app(pool.clone(), "/nonexistent");
        let account = sessions::tests::new_account(&pool).await;
        let (a, b) = (
            sessions::tests::new_session(&pool, account).await,
            sessions::tests::new_session(&pool, account).await,
        );
        let unauthorized = (
            StatusCode::UNAUTHORIZED,
            json!({"error": "unauthorized", "message": "Please sign in again."}).to_string(),
        );
        assert_eq!(logout(&app, None).await, unauthorized);
        assert_eq!(logout(&app, Some("not-a-token")).await, unauthorized);
        assert_eq!(logout(&app, Some(&a)).await.0, StatusCode::NO_CONTENT);
        assert_eq!(logout(&app, Some(&a)).await, unauthorized);

        sqlx::query!(
            "UPDATE sessions SET expires_at = now() WHERE account_id = $1",
            account
        )
        .execute(&pool)
        .await
        .unwrap();
        assert_eq!(logout(&app, Some(&b)).await, unauthorized);
    }

    #[tokio::test]
    async fn location_needs_a_session_validates_and_logout_drops_it() {
        let Some(pool) = sessions::tests::test_pool().await else {
            return;
        };
        let state = test_state(pool.clone());
        let app = app(state.clone());
        let account = sessions::tests::new_account(&pool).await;
        let token = sessions::tests::new_session(&pool, account).await;
        let ok = r#"{"lat":51.5,"lon":-0.12,"accuracyM":5.0}"#;

        assert_eq!(
            post_location(&app, None, ok).await.0,
            StatusCode::UNAUTHORIZED
        );
        for bad in [
            r#"{"lat":91.0,"lon":0.0,"accuracyM":5.0}"#,
            r#"{"lat":0.0,"lon":-181,"accuracyM":5.0}"#,
            r#"{"lat":0.0,"lon":0.0,"accuracyM":-1}"#,
        ] {
            let (status, body) = post_location(&app, Some(&token), bad).await;
            assert_eq!(status, StatusCode::BAD_REQUEST, "{bad}");
            assert!(body.contains(r#""error":"invalid_fix""#), "{body}");
        }
        // JSON has no NaN, so a NaN can only arrive malformed.
        for bad in [r#"{"lat":NaN,"lon":0.0,"accuracyM":5.0}"#, r#"{"lat":0.0}"#] {
            let (status, body) = post_location(&app, Some(&token), bad).await;
            assert_eq!(status, StatusCode::BAD_REQUEST, "{bad}");
            assert!(body.contains(r#""error":"bad_request""#), "{body}");
        }
        assert!(!state.positions.has(account));

        assert_eq!(
            post_location(&app, Some(&token), ok).await,
            (StatusCode::NO_CONTENT, String::new())
        );
        assert!(state.positions.has(account));
        assert_eq!(logout(&app, Some(&token)).await.0, StatusCode::NO_CONTENT);
        assert!(!state.positions.has(account));
    }

    type Client = tokio_tungstenite::WebSocketStream<
        tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>,
    >;

    /// A real listener: the upgrade needs a real connection.
    async fn serve(state: AppState) -> String {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("ws://{}/v1/ws", listener.local_addr().unwrap());
        tokio::spawn(async move { axum::serve(listener, app(state)).await });
        url
    }

    async fn connect(
        url: &str,
        token: Option<&str>,
    ) -> Result<Client, tokio_tungstenite::tungstenite::Error> {
        use tokio_tungstenite::tungstenite::client::IntoClientRequest;
        let mut req = url.into_client_request().unwrap();
        if let Some(t) = token {
            let bearer = format!("Bearer {t}").parse().unwrap();
            req.headers_mut().insert("Authorization", bearer);
        }
        tokio_tungstenite::connect_async(req)
            .await
            .map(|(ws, _)| ws)
    }

    async fn next_text(ws: &mut Client) -> String {
        use futures_util::StreamExt;
        match ws.next().await {
            Some(Ok(tokio_tungstenite::tungstenite::Message::Text(t))) => t.to_string(),
            other => panic!("expected text, got {other:?}"),
        }
    }

    async fn round_trip(ws: &mut Client, msg: &str) -> String {
        use futures_util::SinkExt;
        ws.send(msg.into()).await.unwrap();
        next_text(ws).await
    }

    #[tokio::test]
    async fn websocket_needs_a_token_says_hello_acks_loc_and_stays_open_on_errors() {
        let Some(pool) = sessions::tests::test_pool().await else {
            return;
        };
        let state = test_state(pool.clone());
        let url = serve(state.clone()).await;
        let account = sessions::tests::new_account(&pool).await;
        let token = sessions::tests::new_session(&pool, account).await;

        for bad in [None, Some("not-a-token")] {
            match connect(&url, bad).await {
                Err(tokio_tungstenite::tungstenite::Error::Http(res)) => {
                    assert_eq!(res.status(), StatusCode::UNAUTHORIZED)
                }
                other => panic!("expected 401, got {:?}", other.map(|_| ())),
            }
        }

        let mut ws = connect(&url, Some(&token)).await.unwrap();
        assert_eq!(next_text(&mut ws).await, r#"{"t":"hello"}"#);
        let cases = [
            (
                r#"{"t":"loc","id":1,"d":{"lat":100.0,"lon":2.0,"accuracyM":3.0}}"#,
                r#"{"t":"err","id":1,"d":{"error":"invalid_fix"}}"#,
            ),
            (
                r#"{"t":"loc","id":2}"#,
                r#"{"t":"err","id":2,"d":{"error":"invalid_fix"}}"#,
            ),
            (
                r#"{"t":"what","id":3}"#,
                r#"{"t":"err","id":3,"d":{"error":"unknown_type"}}"#,
            ),
            ("not json", r#"{"t":"err","d":{"error":"bad_request"}}"#),
        ];
        for (send, want) in cases {
            assert_eq!(round_trip(&mut ws, send).await, want, "{send}");
        }
        assert!(!state.positions.has(account));
        let loc = r#"{"t":"loc","id":4,"d":{"lat":1.0,"lon":2.0,"accuracyM":3.0}}"#;
        assert_eq!(round_trip(&mut ws, loc).await, r#"{"t":"ack","id":4}"#);
        assert!(state.positions.has(account));
    }

    #[tokio::test]
    async fn websocket_pings_every_30_s_and_closes_after_60_s_of_silence() {
        use futures_util::StreamExt;
        use tokio_tungstenite::tungstenite::Message;
        let Some(pool) = sessions::tests::test_pool().await else {
            return;
        };
        let url = serve(test_state(pool.clone())).await;
        let account = sessions::tests::new_account(&pool).await;
        let token = sessions::tests::new_session(&pool, account).await;
        let mut ws = connect(&url, Some(&token)).await.unwrap();
        assert_eq!(next_text(&mut ws).await, r#"{"t":"hello"}"#);

        // Paused only now, so the database round trips above ran on real time.
        tokio::time::pause();
        let start = tokio::time::Instant::now();
        // Silent client: it neither reads nor answers until the server has given up.
        tokio::time::sleep(Duration::from_secs(61)).await;
        // Bounded, so a server that never closes fails instead of hanging.
        let (mut pings, mut closed) = (0, false);
        for _ in 0..3 {
            match ws.next().await {
                Some(Ok(Message::Ping(_))) => pings += 1,
                Some(Ok(other)) => panic!("unexpected {other:?}"),
                Some(Err(_)) | None => {
                    closed = true;
                    break;
                }
            }
        }
        assert!(closed && (1..=2).contains(&pings), "{pings} pings");
        assert!(start.elapsed() < Duration::from_secs(62));
    }

    /// Every log line from now on, at every level and from every crate. Global,
    /// because a thread-local subscriber misses callsites other test threads
    /// registered first; the other tests' lines land here too, which is harmless.
    fn captured_logs() -> String {
        #[derive(Clone, Default)]
        struct Buf(Arc<std::sync::Mutex<Vec<u8>>>);
        impl std::io::Write for Buf {
            fn write(&mut self, b: &[u8]) -> std::io::Result<usize> {
                self.0.lock().unwrap().extend_from_slice(b);
                Ok(b.len())
            }
            fn flush(&mut self) -> std::io::Result<()> {
                Ok(())
            }
        }
        static LOGS: std::sync::OnceLock<Buf> = std::sync::OnceLock::new();
        let buf = LOGS.get_or_init(|| {
            let buf = Buf::default();
            let writer = buf.clone();
            tracing_subscriber::fmt()
                .with_max_level(tracing::Level::TRACE)
                .with_ansi(false)
                .with_writer(move || writer.clone())
                .init();
            buf
        });
        String::from_utf8_lossy(&buf.0.lock().unwrap()).into_owned()
    }

    #[tokio::test]
    async fn session_tokens_and_positions_never_reach_the_log() {
        let Some(pool) = sessions::tests::test_pool().await else {
            return;
        };
        let app = test_app(pool.clone(), "/nonexistent");
        let account = sessions::tests::new_account(&pool).await;
        let token = sessions::tests::new_session(&pool, account).await;
        captured_logs();
        let fix = r#"{"lat":12.3456789,"lon":98.7654321,"accuracyM":4.25}"#;
        assert_eq!(
            post_location(&app, Some(&token), fix).await.0,
            StatusCode::NO_CONTENT
        );
        assert_eq!(logout(&app, Some(&token)).await.0, StatusCode::NO_CONTENT);
        assert_eq!(logout(&app, Some(&token)).await.0, StatusCode::UNAUTHORIZED);
        let (_, body) = post_json(&app, "/v1/auth/register/options", json!({})).await;
        let verify = json!({"ceremonyId": body["ceremonyId"], "response": token});
        post_json(&app, "/v1/auth/register/verify", verify).await;
        let log = captured_logs();
        assert!(log.contains("POST /v1/auth/logout 204"), "{log}");
        assert!(log.contains("POST /v1/auth/register/verify 400"), "{log}");
        assert!(log.contains("POST /v1/location 204"), "{log}");
        assert!(!log.contains(&token), "{log}");
        for n in ["12.345", "98.765", "4.25"] {
            assert!(!log.contains(n), "{log}");
        }
    }
}
