use axum::{extract::Request, http::StatusCode, middleware::Next, response::Response};
use subtle::ConstantTimeEq;
use tracing::warn;

/// Read AUTH_TOKEN from environment. Panics at startup if not set or empty.
/// Call `require_auth_token()` once during startup to enforce this invariant.
fn expected_token() -> String {
    let token = std::env::var("AUTH_TOKEN")
        .unwrap_or_default();
    if token.is_empty() {
        eprintln!("FATAL: AUTH_TOKEN environment variable is not set or is empty.");
        eprintln!("Set AUTH_TOKEN to a strong secret before starting the server.");
        std::process::exit(1);
    }
    token
}

/// Call once at startup to abort if AUTH_TOKEN is missing.
pub fn require_auth_token() {
    let _ = expected_token();
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
/// AUTH_TOKEN must be set — if it isn't, the server exits at startup.
pub async fn auth_middleware(request: Request, next: Next) -> Result<Response, StatusCode> {
    let expected = expected_token();

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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_extract_token_from_query_found() {
        let query = "foo=bar&token=secret&baz=1";
        assert_eq!(extract_token_from_query(query), Some("secret".to_string()));
    }

    #[test]
    fn test_extract_token_first_param() {
        let query = "token=mytoken";
        assert_eq!(extract_token_from_query(query), Some("mytoken".to_string()));
    }

    #[test]
    fn test_extract_token_not_found() {
        let query = "foo=bar&baz=1";
        assert_eq!(extract_token_from_query(query), None);
    }

    #[test]
    fn test_extract_token_empty_query() {
        assert_eq!(extract_token_from_query(""), None);
    }

    #[test]
    fn test_extract_token_url_encoded() {
        let query = "token=hello%20world";
        assert_eq!(extract_token_from_query(query), Some("hello world".to_string()));
    }

    #[test]
    fn test_percent_decode_no_encoding() {
        assert_eq!(percent_decode(b"hello"), "hello");
    }

    #[test]
    fn test_percent_decode_space() {
        assert_eq!(percent_decode(b"hello%20world"), "hello world");
    }

    #[test]
    fn test_percent_decode_special_chars() {
        assert_eq!(percent_decode(b"foo%3Dbar"), "foo=bar");
    }

    #[test]
    fn test_percent_decode_partial_sequence() {
        // Incomplete percent sequence at end — should be passed through
        let result = percent_decode(b"hello%2");
        assert!(result.starts_with("hello"));
    }

    #[test]
    fn test_tokens_match_same() {
        assert!(tokens_match("secret", "secret"));
    }

    #[test]
    fn test_tokens_match_different() {
        assert!(!tokens_match("wrong", "secret"));
    }

    #[test]
    fn test_tokens_match_different_length() {
        assert!(!tokens_match("short", "longer_secret"));
    }

    #[test]
    fn test_tokens_match_empty() {
        assert!(tokens_match("", ""));
    }

    #[test]
    fn test_percent_decode_multiple_encoded() {
        assert_eq!(percent_decode(b"a%20b%3Dc"), "a b=c");
    }

    #[test]
    fn test_percent_decode_invalid_hex() {
        // %GG is not valid hex — should be passed through
        let result = percent_decode(b"hello%GGworld");
        // The exact behavior depends on implementation: may skip or pass through
        assert!(result.contains("hello"));
        assert!(result.contains("world"));
    }

    #[test]
    fn test_extract_token_multiple_params() {
        let query = "token=first&token=second";
        assert_eq!(extract_token_from_query(query), Some("first".to_string()));
    }
}
