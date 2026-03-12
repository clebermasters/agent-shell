use axum::{extract::Request, http::StatusCode, middleware::Next, response::Response};
use subtle::ConstantTimeEq;
use tracing::warn;

/// Read AUTH_TOKEN from environment. If not set, auth is disabled (open access).
fn expected_token() -> Option<String> {
    let token = std::env::var("AUTH_TOKEN").ok()?;
    if token.is_empty() {
        None
    } else {
        Some(token)
    }
}

/// Extract token value from a query string like "foo=bar&token=secret&baz=1"
fn extract_token_from_query(query: &str) -> Option<String> {
    for pair in query.split('&') {
        if let Some(value) = pair.strip_prefix("token=") {
            // URL-decode the value
            return Some(percent_decode(value.as_bytes()));
        }
    }
    None
}

/// Simple percent-decoding (handles %XX sequences)
fn percent_decode(input: &[u8]) -> String {
    let mut output = Vec::with_capacity(input.len());
    let mut i = 0;
    while i < input.len() {
        if input[i] == b'%' && i + 2 < input.len() {
            if let Ok(byte) = u8::from_str_radix(
                &String::from_utf8_lossy(&input[i + 1..i + 3]),
                16,
            ) {
                output.push(byte);
                i += 3;
                continue;
            }
        }
        output.push(input[i]);
        i += 1;
    }
    String::from_utf8_lossy(&output).to_string()
}

/// Constant-time token comparison to prevent timing attacks.
fn tokens_match(provided: &str, expected: &str) -> bool {
    // Constant-time comparison — both length and content are compared
    // without leaking information through timing side-channels.
    provided.as_bytes().ct_eq(expected.as_bytes()).into()
}

/// Return the request path without query string (strips token from logged URIs).
fn safe_path(request: &Request) -> &str {
    request.uri().path()
}

/// Middleware that validates the auth token from query param or header.
/// If AUTH_TOKEN env var is not set, all requests are allowed (backwards compatible).
pub async fn auth_middleware(request: Request, next: Next) -> Result<Response, StatusCode> {
    let expected = match expected_token() {
        Some(t) => t,
        None => return Ok(next.run(request).await), // No token configured, allow all
    };

    // Check query parameter first (?token=xxx)
    let token_from_query = request
        .uri()
        .query()
        .and_then(extract_token_from_query);

    // Check X-Auth-Token header as fallback
    let token_from_header = request
        .headers()
        .get("X-Auth-Token")
        .and_then(|v| v.to_str().ok())
        .map(String::from);

    let provided = token_from_query.or(token_from_header);

    match provided {
        Some(ref t) if tokens_match(t, &expected) => Ok(next.run(request).await),
        Some(_) => {
            warn!(
                "Rejected request with invalid auth token: {} {}",
                request.method(),
                safe_path(&request)
            );
            Err(StatusCode::UNAUTHORIZED)
        }
        None => {
            warn!(
                "Rejected request with missing auth token: {} {}",
                request.method(),
                safe_path(&request)
            );
            Err(StatusCode::UNAUTHORIZED)
        }
    }
}
