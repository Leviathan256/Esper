//! The §5 error body: `{"error": "<code>", "message": "<safe to show>"}`.

use axum::{
    Json,
    extract::rejection::JsonRejection,
    http::StatusCode,
    response::{IntoResponse, Response},
};

#[derive(Debug)]
pub struct ApiError {
    status: StatusCode,
    code: &'static str,
    message: &'static str,
}

impl ApiError {
    const fn new(status: StatusCode, code: &'static str, message: &'static str) -> Self {
        Self {
            status,
            code,
            message,
        }
    }

    pub const BAD_REQUEST: Self = Self::new(
        StatusCode::BAD_REQUEST,
        "bad_request",
        "The request was malformed.",
    );
    pub const INVALID_CEREMONY: Self = Self::new(
        StatusCode::BAD_REQUEST,
        "invalid_ceremony",
        "Sign-in took too long. Please try again.",
    );
    pub const PASSKEY_REJECTED: Self = Self::new(
        StatusCode::BAD_REQUEST,
        "passkey_rejected",
        "The passkey was not accepted.",
    );
    pub const UNAUTHORIZED: Self = Self::new(
        StatusCode::UNAUTHORIZED,
        "unauthorized",
        "Please sign in again.",
    );
    pub const NOT_FOUND: Self = Self::new(StatusCode::NOT_FOUND, "not_found", "Not found.");
    pub const RATE_LIMITED: Self = Self::new(
        StatusCode::TOO_MANY_REQUESTS,
        "rate_limited",
        "Too many attempts. Wait a minute and try again.",
    );
    pub const INTERNAL: Self = Self::new(
        StatusCode::INTERNAL_SERVER_ERROR,
        "internal",
        "Something went wrong on the server.",
    );
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let body = serde_json::json!({"error": self.code, "message": self.message});
        (self.status, Json(body)).into_response()
    }
}

/// Malformed JSON, a missing field or a body over the 64 KB limit.
impl From<JsonRejection> for ApiError {
    fn from(_: JsonRejection) -> Self {
        Self::BAD_REQUEST
    }
}

impl From<sqlx::Error> for ApiError {
    fn from(e: sqlx::Error) -> Self {
        tracing::error!("database: {e}");
        Self::INTERNAL
    }
}
