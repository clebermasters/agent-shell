//! Browser control is authenticated by the normal API; the binary stream gets a
//! single-use bootstrap grant and a runtime-scoped HttpOnly cookie instead.
use axum::{
    body::Body,
    extract::{
        ws::{Message, WebSocket},
        DefaultBodyLimit, Form, Path, WebSocketUpgrade,
    },
    http::{header, HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use futures::{SinkExt, StreamExt};
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    path::PathBuf,
    sync::{Arc, LazyLock},
    time::{Duration, Instant},
};
use tokio::sync::Mutex;
use tokio_tungstenite::{
    connect_async,
    tungstenite::{client::IntoClientRequest, Message as UpMessage},
};
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

type ApiResult = Result<Response, BrowserError>;
const UI_FILE: &str = "assets/ui-BOjwDkC7.js";
const UI_SHA: &str = "351b7a0b3b05261b8bff17419e28a8216f691e2eee1e6800842f4773a143c727";
const HTML_SHA: &str = "e83beef3521001888e20a47a261165f9507acc1e5588f3ba2b384ac3d7a68844";
const BRIDGE: &str = include_str!("../../deployment/browser/viewer-bridge.js");
const TOUCH_MOUSE: &str = include_str!("../../deployment/browser/touch-mouse.js");

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Config {
    session_id: Uuid,
    port: u16,
    password: String,
    runner: PathBuf,
    public_origin: String,
    #[serde(default)]
    additional_origins: Vec<String>,
    #[serde(default = "default_label")]
    display_name: String,
    #[serde(default)]
    allow_insecure_loopback: bool,
}
fn default_label() -> String {
    "Chrome".into()
}

struct Ticket {
    viewer: Uuid,
    generation: String,
    expires: Instant,
    origin: String,
}
struct Controller {
    viewer: Uuid,
    connection: Uuid,
    cancel: CancellationToken,
    lease_expires: Instant,
}
struct Reservation {
    viewer: Uuid,
    expires: Instant,
}
struct Handoff {
    id: Uuid,
    source: Uuid,
    generation: String,
    target: Option<Uuid>,
    expires: Instant,
}
#[derive(Default)]
struct Access {
    grants: HashMap<String, Ticket>,
    cookies: HashMap<String, Ticket>,
    controller: Option<Controller>,
    reservation: Option<Reservation>,
    handoff: Option<Handoff>,
    cancelled: HashMap<Uuid, Instant>,
}
impl Access {
    fn expire_controller(&mut self) {
        self.cancelled
            .retain(|_, expires| *expires > Instant::now());
        if self
            .handoff
            .as_ref()
            .is_some_and(|h| h.expires <= Instant::now())
        {
            self.handoff = None;
        }
        if self
            .reservation
            .as_ref()
            .is_some_and(|r| r.expires <= Instant::now())
        {
            self.reservation = None;
        }
        if self
            .controller
            .as_ref()
            .is_some_and(|c| c.lease_expires <= Instant::now())
        {
            if let Some(controller) = self.controller.take() {
                controller.cancel.cancel();
            }
        }
    }
    fn in_use_by_other(&self, viewer: Uuid) -> bool {
        self.controller.as_ref().is_some_and(|c| c.viewer != viewer)
            || self
                .reservation
                .as_ref()
                .is_some_and(|r| r.viewer != viewer)
    }
    fn take_control(&mut self, viewer: Uuid) {
        if let Some(handoff) = self.handoff.take() {
            self.cancelled
                .insert(handoff.id, Instant::now() + Duration::from_secs(120));
        }
        if let Some(controller) = self.controller.take() {
            controller.cancel.cancel();
        }
        // Revoke outstanding access as well as the live connection. An old
        // viewer cannot win a reconnect race while the new viewer loads.
        self.cookies.clear();
        self.grants.clear();
        self.reservation = Some(Reservation {
            viewer,
            expires: Instant::now() + Duration::from_secs(60),
        });
    }
    fn cancel_handoff(
        &mut self,
        id: Uuid,
        source: Uuid,
        generation: &str,
    ) -> Result<(), BrowserError> {
        self.expire_controller();
        let owned = self
            .handoff
            .as_ref()
            .is_some_and(|h| h.id == id && h.source == source && h.generation == generation);
        if !owned && (self.handoff.is_some() || self.in_use_by_other(source)) {
            return Err(error("HANDOFF_SUPERSEDED"));
        }
        if self.cancelled.len() >= 64 && !self.cancelled.contains_key(&id) {
            return Err(error("VIEWER_LIMIT_REACHED"));
        }
        self.cancelled
            .insert(id, Instant::now() + Duration::from_secs(120));
        if owned {
            self.handoff = None;
        }
        Ok(())
    }
    fn grant_control(&mut self, request: &GrantRequest) -> Result<(), BrowserError> {
        if let Some(id) = request.cancel_handoff_id {
            if !request.take_control || request.handoff_id.is_some() {
                return Err(error("INVALID_HANDOFF"));
            }
            self.cancel_handoff(id, request.viewer_id, &request.runtime_generation)?;
        }
        if let Some(id) = request.handoff_id {
            if !request.take_control
                || self.cancelled.contains_key(&id)
                || !self.handoff.as_ref().is_some_and(|h| {
                    h.id == id && h.generation == request.runtime_generation && h.target.is_none()
                })
            {
                return Err(error("HANDOFF_EXPIRED"));
            }
            let mut handoff = self.handoff.take().unwrap();
            self.take_control(request.viewer_id);
            handoff.target = Some(request.viewer_id);
            self.handoff = Some(handoff);
        } else if request.take_control {
            self.take_control(request.viewer_id);
        }
        Ok(())
    }
    fn release_viewer(&mut self, viewer: Uuid) {
        if self.controller.as_ref().is_some_and(|c| c.viewer == viewer) {
            self.controller.take().unwrap().cancel.cancel();
        }
        if self
            .reservation
            .as_ref()
            .is_some_and(|r| r.viewer == viewer)
        {
            self.reservation = None;
        }
        self.cookies.retain(|_, t| t.viewer != viewer);
        self.grants.retain(|_, t| t.viewer != viewer);
    }
}
struct Service {
    config: Option<(PathBuf, Config)>,
    access: Mutex<Access>,
    runtime: Mutex<()>,
    handoff: Mutex<()>,
}

static SERVICE: LazyLock<Service> = LazyLock::new(|| {
    let path = std::env::var_os("AGENTSHELL_BROWSER_CONFIG")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            dirs::home_dir()
                .unwrap_or_default()
                .join(".config/agentshell/browser.json")
        });
    let config = std::fs::read(&path)
        .ok()
        .and_then(|bytes| serde_json::from_slice::<Config>(&bytes).ok())
        .filter(|c| valid_origin(c) && c.port >= 1024 && c.password.len() >= 24)
        .map(|c| (path, c));
    Service {
        config,
        access: Mutex::new(Access::default()),
        runtime: Mutex::new(()),
        handoff: Mutex::new(()),
    }
});

fn valid_origin(config: &Config) -> bool {
    config.additional_origins.len() <= 8
        && std::iter::once(&config.public_origin).chain(&config.additional_origins)
            .all(|origin| valid_origin_value(origin, config.allow_insecure_loopback))
}
fn valid_origin_value(origin: &str, allow_insecure_loopback: bool) -> bool {
    let Ok(url) = reqwest::Url::parse(origin) else {
        return false;
    };
    let allowed = url.scheme() == "https"
        || (allow_insecure_loopback
            && url.scheme() == "http"
            && matches!(url.host_str(), Some("127.0.0.1" | "localhost")));
    allowed
        && url.username().is_empty()
        && url.password().is_none()
        && url.query().is_none()
        && url.fragment().is_none()
        && url.path() == "/"
        && origin == url.origin().ascii_serialization()
}
fn viewer_origins(config: &Config) -> Vec<&str> {
    std::iter::once(config.public_origin.as_str())
        .chain(config.additional_origins.iter().map(String::as_str)).collect()
}
fn grant_origin<'a>(config: &'a Config, requested: Option<&'a str>) -> Result<&'a str, BrowserError> {
    let origin = requested.unwrap_or(&config.public_origin);
    if viewer_origins(config).contains(&origin) { Ok(origin) } else { Err(unauthorized()) }
}

#[derive(Debug)]
struct BrowserError(StatusCode, &'static str);
impl IntoResponse for BrowserError {
    fn into_response(self) -> Response {
        (self.0, Json(json!({"code":self.1}))).into_response()
    }
}
fn error(code: &'static str) -> BrowserError {
    BrowserError(StatusCode::CONFLICT, code)
}
fn unauthorized() -> BrowserError {
    BrowserError(StatusCode::UNAUTHORIZED, "VIEWER_AUTHORIZATION_REQUIRED")
}
fn configured() -> Result<&'static (PathBuf, Config), BrowserError> {
    SERVICE
        .config
        .as_ref()
        .ok_or_else(|| BrowserError(StatusCode::SERVICE_UNAVAILABLE, "BROWSER_NOT_CONFIGURED"))
}
fn id_matches(id: &str, config: &Config) -> Result<(), BrowserError> {
    if Uuid::parse_str(id).ok() != Some(config.session_id) {
        return Err(BrowserError(
            StatusCode::NOT_FOUND,
            "BROWSER_SESSION_NOT_FOUND",
        ));
    }
    Ok(())
}
fn secret() -> String {
    format!("{}{}", Uuid::new_v4().simple(), Uuid::new_v4().simple())
}
fn no_store(mut response: Response) -> Response {
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response.headers_mut().insert(
        header::REFERRER_POLICY,
        HeaderValue::from_static("no-referrer"),
    );
    response
}
async fn runtime(command: &str) -> Result<Value, BrowserError> {
    let (path, config) = configured()?;
    let output = tokio::time::timeout(
        Duration::from_secs(20),
        tokio::process::Command::new("/usr/bin/python3")
            .arg(&config.runner)
            .arg("--config")
            .arg(path)
            .arg(command)
            .kill_on_drop(true)
            .output(),
    )
    .await
    .map_err(|_| error("RUNTIME_TIMEOUT"))?
    .map_err(|_| error("RUNTIME_HELPER_UNAVAILABLE"))?;
    if !output.status.success() || output.stdout.len() > 4096 {
        return Err(error("RUNTIME_HELPER_FAILED"));
    }
    serde_json::from_slice(&output.stdout).map_err(|_| error("RUNTIME_HELPER_FAILED"))
}
async fn ready_generation() -> Result<String, BrowserError> {
    let state = runtime("status").await?;
    if state["state"] != "ready" {
        return Err(error("BROWSER_NOT_READY"));
    }
    state["runtimeGeneration"]
        .as_str()
        .map(str::to_owned)
        .ok_or_else(|| error("RUNTIME_HELPER_FAILED"))
}

pub fn api_router() -> Router<Arc<crate::AppState>> {
    Router::new()
        .route("/api/browser/capabilities", get(capabilities))
        .route("/api/browser/sessions", post(start))
        .route("/api/browser/sessions/:id", get(inspect))
        .route("/api/browser/sessions/:id/viewer-grants", post(grant))
        .route(
            "/api/browser/sessions/:id/desktop-handoff",
            post(desktop_handoff),
        )
        .route(
            "/api/browser/sessions/:id/native-handoff",
            post(native_handoff),
        )
        .route(
            "/api/browser/sessions/:id/release-viewer",
            post(release_viewer),
        )
        .route("/api/browser/sessions/:id/stop", post(stop))
        .layer(DefaultBodyLimit::max(8192))
}
pub fn viewer_router() -> Router<Arc<crate::AppState>> {
    Router::new()
        .route("/browser/:id/bootstrap", post(bootstrap))
        .route("/browser/:id/socket", get(socket))
        .route("/browser/:id/view/*path", get(asset))
        .layer(DefaultBodyLimit::max(8192))
}
async fn capabilities() -> ApiResult {
    let Ok((_, config)) = configured() else {
        return Ok(no_store(
            Json(json!({"available":false,"code":"BROWSER_NOT_CONFIGURED"})).into_response(),
        ));
    };
    let state = runtime("status").await?;
    Ok(no_store(
        Json(
            json!({"available":true,"transport":"kasmvnc","displayName":config.display_name,
        "sessionId":config.session_id,"viewerOrigin":config.public_origin,"viewerOrigins":viewer_origins(config),"session":state,
        "httpPort":super::read_port_from_env("AGENTSHELL_HTTP_PORT",4010),
        "httpsPort":super::read_port_from_env("AGENTSHELL_HTTPS_PORT",4443),
        "desktopHandoff":true,"mobileHandoff":true,"nativeHandoff":true}),
        )
        .into_response(),
    ))
}
async fn start() -> ApiResult {
    let _guard = SERVICE.runtime.lock().await;
    let state = runtime("start").await?;
    Ok(no_store(Json(state).into_response()))
}
async fn inspect(Path(id): Path<String>) -> ApiResult {
    id_matches(&id, &configured()?.1)?;
    Ok(no_store(Json(runtime("status").await?).into_response()))
}
async fn stop(Path(id): Path<String>) -> ApiResult {
    id_matches(&id, &configured()?.1)?;
    let _guard = SERVICE.runtime.lock().await;
    let mut access = SERVICE.access.lock().await;
    if let Some(controller) = access.controller.take() {
        controller.cancel.cancel();
    }
    access.cookies.clear();
    access.grants.clear();
    access.reservation = None;
    access.handoff = None;
    drop(access);
    Ok(no_store(Json(runtime("stop").await?).into_response()))
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct GrantRequest {
    viewer_id: Uuid,
    runtime_generation: String,
    #[serde(default)]
    take_control: bool,
    #[serde(default)]
    handoff_id: Option<Uuid>,
    #[serde(default)]
    cancel_handoff_id: Option<Uuid>,
    #[serde(default)]
    viewer_origin: Option<String>,
}
async fn grant(Path(id): Path<String>, Json(request): Json<GrantRequest>) -> ApiResult {
    let config = &configured()?.1;
    id_matches(&id, config)?;
    let origin = grant_origin(config, request.viewer_origin.as_deref())?.to_owned();
    let generation = ready_generation().await?;
    if generation != request.runtime_generation {
        return Err(error("RUNTIME_CHANGED"));
    }
    let mut access = SERVICE.access.lock().await;
    let now = Instant::now();
    access.expire_controller();
    access.grants.retain(|_, g| g.expires > now);
    access.cookies.retain(|_, c| c.expires > now);
    access.grant_control(&request)?;
    if access.grants.len() >= 64 || access.cookies.len() >= 64 {
        return Err(error("VIEWER_LIMIT_REACHED"));
    }
    if access.in_use_by_other(request.viewer_id) {
        return Err(error("CONTROLLER_IN_USE"));
    }
    let grant = secret();
    access
        .grants
        .retain(|_, grant| grant.viewer != request.viewer_id);
    access.grants.insert(
        grant.clone(),
        Ticket {
            viewer: request.viewer_id,
            generation,
            expires: now + Duration::from_secs(60),
            origin,
        },
    );
    Ok(no_store(
        Json(json!({"grant":grant,"expiresIn":60})).into_response(),
    ))
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ReleaseRequest {
    viewer_id: Uuid,
    runtime_generation: String,
}
async fn release_viewer(Path(id): Path<String>, Json(request): Json<ReleaseRequest>) -> ApiResult {
    id_matches(&id, &configured()?.1)?;
    if ready_generation().await? != request.runtime_generation {
        return Err(error("RUNTIME_CHANGED"));
    }
    // Releasing the desktop's identity must never disconnect an unrelated
    // controller that has already taken over (for example, Android).
    SERVICE
        .access
        .lock()
        .await
        .release_viewer(request.viewer_id);
    Ok(no_store(Json(json!({"released":true})).into_response()))
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct HandoffRequest {
    viewer_id: Uuid,
    runtime_generation: String,
    handoff_id: Uuid,
}
async fn desktop_handoff(Path(id): Path<String>, Json(request): Json<HandoffRequest>) -> ApiResult {
    let (path, config) = configured()?;
    id_matches(&id, config)?;
    let _guard = SERVICE
        .handoff
        .try_lock()
        .map_err(|_| error("HANDOFF_IN_PROGRESS"))?;
    if ready_generation().await? != request.runtime_generation {
        return Err(error("RUNTIME_CHANGED"));
    }
    {
        let mut access = SERVICE.access.lock().await;
        access.expire_controller();
        if access.cancelled.contains_key(&request.handoff_id) {
            return Err(error("HANDOFF_EXPIRED"));
        }
        if access.cancelled.len() >= 64 {
            return Err(error("VIEWER_LIMIT_REACHED"));
        }
        let authorized_source = access
            .controller
            .as_ref()
            .is_some_and(|c| c.viewer == request.viewer_id)
            || access.cookies.values().any(|t| {
                t.viewer == request.viewer_id
                    && t.generation == request.runtime_generation
                    && t.expires > Instant::now()
            });
        if !authorized_source || access.in_use_by_other(request.viewer_id) {
            return Err(error("CONTROLLER_CHANGED"));
        }
        access.handoff = Some(Handoff {
            id: request.handoff_id,
            source: request.viewer_id,
            generation: request.runtime_generation.clone(),
            target: None,
            expires: Instant::now() + Duration::from_secs(120),
        });
    }
    // Fixed installed helper/configuration only: the app cannot submit paths,
    // environment variables, shell commands or a desktop DISPLAY.
    let helper = config.runner.with_file_name("browser-desktop.py");
    let desktop_config = path.with_file_name("browser-desktop.json");
    let output = tokio::time::timeout(
        Duration::from_secs(55),
        tokio::process::Command::new("/usr/bin/python3")
            .arg(helper)
            .arg("--config")
            .arg(desktop_config)
            .args(["--from-app", "--json", "--handoff-id"])
            .arg(request.handoff_id.to_string())
            .arg("--generation")
            .arg(&request.runtime_generation)
            .arg("desktop")
            .kill_on_drop(true)
            .output(),
    )
    .await;
    let result = output
        .ok()
        .and_then(Result::ok)
        .filter(|o| o.stdout.len() <= 4096)
        .and_then(|o| serde_json::from_slice::<Value>(&o.stdout).ok());
    let mut access = SERVICE.access.lock().await;
    let owned = access
        .handoff
        .as_ref()
        .is_some_and(|h| h.id == request.handoff_id);
    if !owned {
        return Err(error("HANDOFF_SUPERSEDED"));
    }
    let connected = access
        .handoff
        .as_ref()
        .and_then(|h| h.target)
        .is_some_and(|target| {
            access
                .controller
                .as_ref()
                .is_some_and(|c| c.viewer == target)
        });
    if connected
        && result
            .as_ref()
            .is_some_and(|r| r["connected"] == true && r["code"] == "CONNECTED")
    {
        return Ok(no_store(
            Json(json!({"destination":"desktop","connected":true})).into_response(),
        ));
    }
    let target_claimed = access.handoff.as_ref().is_some_and(|h| h.target.is_some());
    access.cancel_handoff(
        request.handoff_id,
        request.viewer_id,
        &request.runtime_generation,
    )?;
    if target_claimed {
        access.take_control(request.viewer_id);
    }
    let code = match result.as_ref().and_then(|r| r["code"].as_str()) {
        Some("CERTIFICATE_ERROR") => "CERTIFICATE_ERROR",
        Some("RUNTIME_CHANGED") => "RUNTIME_CHANGED",
        Some("HANDOFF_EXPIRED") => "HANDOFF_EXPIRED",
        Some("VIEWER_TIMEOUT") => "DESKTOP_CONNECTION_FAILED",
        _ => "DESKTOP_UNAVAILABLE",
    };
    Ok(no_store(
        (
            StatusCode::CONFLICT,
            Json(json!({"code":code,"phonePreserved":!target_claimed})),
        )
            .into_response(),
    ))
}
#[derive(Deserialize)]
struct Bootstrap {
    grant: String,
}
#[derive(Deserialize)]
#[serde(rename_all = "lowercase")]
enum NativeDestination {
    Desktop,
    Phone,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct NativeRequest {
    viewer_id: Uuid,
    runtime_generation: String,
    destination: NativeDestination,
}
async fn native_handoff(Path(id): Path<String>, Json(request): Json<NativeRequest>) -> ApiResult {
    let (path, config) = configured()?;
    id_matches(&id, config)?;
    let _handoff = SERVICE
        .handoff
        .try_lock()
        .map_err(|_| error("HANDOFF_IN_PROGRESS"))?;
    let _runtime = SERVICE
        .runtime
        .try_lock()
        .map_err(|_| error("HANDOFF_IN_PROGRESS"))?;
    let before = runtime("status").await?;
    if before["runtimeGeneration"].as_str() != Some(&request.runtime_generation) {
        return Err(error("RUNTIME_CHANGED"));
    }
    let direction = match request.destination {
        NativeDestination::Desktop => {
            if before["state"] != "ready" {
                return Err(error("BROWSER_NOT_READY"));
            }
            let mut access = SERVICE.access.lock().await;
            access.expire_controller();
            let source = access
                .controller
                .as_ref()
                .is_some_and(|c| c.viewer == request.viewer_id)
                || access.cookies.values().any(|t| {
                    t.viewer == request.viewer_id
                        && t.generation == request.runtime_generation
                        && t.expires > Instant::now()
                });
            if !source || access.in_use_by_other(request.viewer_id) {
                return Err(error("CONTROLLER_CHANGED"));
            }
            "desktop"
        }
        NativeDestination::Phone => {
            if before["state"] != "native" && before["state"] != "native-closed" {
                return Err(error("BROWSER_NOT_READY"));
            }
            "phone"
        }
    };
    let output = tokio::time::timeout(
        Duration::from_secs(75),
        tokio::process::Command::new("/usr/bin/python3")
            .arg(config.runner.with_file_name("browser-native.py"))
            .arg("--config")
            .arg(path)
            .arg("--desktop-config")
            .arg(path.with_file_name("browser-desktop.json"))
            .arg("--from-app")
            .arg("--generation")
            .arg(&request.runtime_generation)
            .arg(direction)
            .kill_on_drop(true)
            .output(),
    )
    .await;
    let result = output
        .ok()
        .and_then(Result::ok)
        .filter(|o| o.stdout.len() <= 4096)
        .and_then(|o| serde_json::from_slice::<Value>(&o.stdout).ok())
        .unwrap_or(json!({"code":"NATIVE_HANDOFF_FAILED","connected":false}));
    let state = runtime("status").await?;
    let preserved = state["state"] == "ready"
        && state["runtimeGeneration"].as_str() == Some(&request.runtime_generation);
    if !preserved {
        let mut access = SERVICE.access.lock().await;
        if let Some(controller) = access.controller.take() {
            controller.cancel.cancel();
        }
        access.cookies.clear();
        access.grants.clear();
        access.reservation = None;
        access.handoff = None;
    }
    if result["connected"] == true
        && ((direction == "desktop" && state["state"] == "native")
            || (direction == "phone" && state["state"] == "ready"))
    {
        return Ok(no_store(Json(json!({"connected":true,"destination":if direction=="desktop"{"native"}else{"phone"},"session":state})).into_response()));
    }
    let code = match result["code"].as_str() {
        Some("CHROME_CLOSE_BLOCKED") => "CHROME_CLOSE_BLOCKED",
        Some("CREDENTIAL_STORE_LOCKED") => "CREDENTIAL_STORE_LOCKED",
        Some("DESKTOP_UNAVAILABLE") => "DESKTOP_UNAVAILABLE",
        Some("RUNTIME_CHANGED") => "RUNTIME_CHANGED",
        Some("PROFILE_OWNER_MISMATCH") => "PROFILE_OWNER_MISMATCH",
        Some("UNCLEAN_CHROME_EXIT") => "UNCLEAN_CHROME_EXIT",
        _ => "NATIVE_HANDOFF_FAILED",
    };
    Ok(no_store(
        (
            StatusCode::CONFLICT,
            Json(json!({"code":code,"phonePreserved":preserved,"session":state})),
        )
            .into_response(),
    ))
}
async fn bootstrap(Path(id): Path<String>, Form(body): Form<Bootstrap>) -> ApiResult {
    let (_, config) = configured()?;
    id_matches(&id, config)?;
    let generation = ready_generation().await?;
    let mut access = SERVICE.access.lock().await;
    let ticket = access.grants.remove(&body.grant).ok_or_else(unauthorized)?;
    if ticket.expires <= Instant::now() || ticket.generation != generation {
        return Err(unauthorized());
    }
    let cookie = secret();
    access
        .cookies
        .retain(|_, existing| existing.viewer != ticket.viewer);
    access.cookies.insert(
        cookie.clone(),
        Ticket {
            viewer: ticket.viewer,
            generation,
            expires: Instant::now() + Duration::from_secs(3600),
            origin: ticket.origin,
        },
    );
    let secure = if config.allow_insecure_loopback {
        ""
    } else {
        "; Secure"
    };
    let location = format!("/browser/{id}/view/vnc.html?autoconnect=true&path=browser/{id}/socket&resize=scale&enable_ime=true&idle_disconnect=0&reconnect=false&clipboard_up=false&clipboard_down=false&enable_webrtc=false");
    let mut response = StatusCode::SEE_OTHER.into_response();
    response.headers_mut().insert(
        header::LOCATION,
        HeaderValue::from_str(&location).map_err(|_| error("INVALID_RUNTIME_CONFIG"))?,
    );
    response.headers_mut().insert(header::SET_COOKIE, HeaderValue::from_str(&format!("agentshell_browser={cookie}; Path=/browser/{id}/; HttpOnly; SameSite=Strict; Max-Age=3600{secure}")).map_err(|_| error("INVALID_RUNTIME_CONFIG"))?);
    Ok(no_store(response))
}
fn cookie(headers: &HeaderMap) -> Result<String, BrowserError> {
    let cookie = headers
        .get(header::COOKIE)
        .and_then(|h| h.to_str().ok())
        .and_then(|h| {
            h.split(';')
                .find_map(|v| v.trim().strip_prefix("agentshell_browser="))
        })
        .ok_or_else(unauthorized)?;
    if cookie.len() != 64 || !cookie.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(unauthorized());
    }
    Ok(cookie.to_owned())
}
async fn authorize(id: &str, headers: &HeaderMap) -> Result<(String, Uuid, String, String), BrowserError> {
    id_matches(id, &configured()?.1)?;
    let cookie = cookie(headers)?;
    let generation = ready_generation().await?;
    let access = SERVICE.access.lock().await;
    let ticket = access.cookies.get(&cookie).ok_or_else(unauthorized)?;
    if ticket.expires <= Instant::now() || ticket.generation != generation {
        return Err(unauthorized());
    }
    Ok((cookie, ticket.viewer, generation, ticket.origin.clone()))
}

async fn asset(Path((id, path)): Path<(String, String)>, headers: HeaderMap) -> ApiResult {
    let (_, _, _, origin) = authorize(&id, &headers).await?;
    if path == "viewer-bridge.js" {
        return Ok(no_store(
            (
                [(header::CONTENT_TYPE, "text/javascript")],
                format!("{TOUCH_MOUSE}\n{BRIDGE}"),
            )
                .into_response(),
        ));
    }
    if path
        .split('/')
        .any(|part| part == ".." || part.starts_with('.'))
        || path.contains('\\')
    {
        return Err(BrowserError(
            StatusCode::NOT_FOUND,
            "VIEWER_ASSET_NOT_FOUND",
        ));
    }
    let base = PathBuf::from("/usr/share/kasmvnc/www");
    let file = tokio::fs::canonicalize(base.join(&path))
        .await
        .map_err(|_| error("VIEWER_ASSET_MISSING"))?;
    if !file.starts_with(&base) {
        return Err(BrowserError(
            StatusCode::NOT_FOUND,
            "VIEWER_ASSET_NOT_FOUND",
        ));
    }
    let mut data = tokio::fs::read(&file)
        .await
        .map_err(|_| error("VIEWER_ASSET_MISSING"))?;
    if data.len() > 8 * 1024 * 1024 {
        return Err(error("VIEWER_ASSET_TOO_LARGE"));
    }
    if path == "vnc.html" {
        if format!("{:x}", Sha256::digest(&data)) != HTML_SHA {
            return Err(error("VIEWER_VERSION_MISMATCH"));
        }
        let html = String::from_utf8(data).map_err(|_| error("VIEWER_VERSION_MISMATCH"))?;
        data = html.replace("</head>", "<script defer src=\"viewer-bridge.js\"></script><style>html,body,#noVNC_container{height:100vh!important;overflow:hidden}.noVNC_logo{display:none}body{background:#111827}</style></head>").into_bytes();
    } else if path == UI_FILE {
        if format!("{:x}", Sha256::digest(&data)) != UI_SHA {
            return Err(error("VIEWER_VERSION_MISMATCH"));
        }
        let js = String::from_utf8(data).map_err(|_| error("VIEWER_VERSION_MISMATCH"))?;
        data = (js.replace(
            "Nb()&&navigator.permissions.query(",
            "Nb()&&navigator.permissions&&navigator.permissions.query(",
        ).replace(
            "function qb(){return an()||ZR()?!1:document.exitPointerLock}",
            "function qb(){return window.BrowserHost||/;\\s*wv\\)/i.test(navigator.userAgent)||an()||ZR()?!1:document.exitPointerLock}",
        ).replace(
            "o.connected&&(document.pointerLockElement!==void 0||document.mozPointerLockElement!==void 0)",
            "o.connected&&qb()",
        ) + "\nwindow.AgentShellUI=o;\n")
            .into_bytes();
    }
    let mime = match file.extension().and_then(|e| e.to_str()).unwrap_or("") {
        "html" => "text/html; charset=utf-8",
        "js" => "text/javascript",
        "css" => "text/css",
        "json" => "application/json",
        "wasm" => "application/wasm",
        "png" => "image/png",
        "jpg" => "image/jpeg",
        "svg" => "image/svg+xml",
        "woff" => "font/woff",
        "ttf" => "font/ttf",
        "mp3" => "audio/mpeg",
        "oga" => "audio/ogg",
        _ => "application/octet-stream",
    };
    let mut response = Response::new(Body::from(data));
    response
        .headers_mut()
        .insert(header::CONTENT_TYPE, HeaderValue::from_static(mime));
    response.headers_mut().insert(
        "X-Content-Type-Options",
        HeaderValue::from_static("nosniff"),
    );
    let websocket_origin = origin.replacen("http", "ws", 1);
    let policy = format!("default-src 'self'; connect-src 'self' {websocket_origin}; script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self'; font-src 'self' data:; worker-src 'self' blob:; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
    response.headers_mut().insert(
        header::CONTENT_SECURITY_POLICY,
        HeaderValue::from_str(&policy).map_err(|_| error("INVALID_RUNTIME_CONFIG"))?,
    );
    Ok(no_store(response))
}

async fn socket(
    Path(id): Path<String>,
    headers: HeaderMap,
    upgrade: WebSocketUpgrade,
) -> ApiResult {
    let (cookie, viewer, generation, origin) = authorize(&id, &headers).await?;
    if headers.get(header::ORIGIN).and_then(|v| v.to_str().ok()) != Some(origin.as_str())
    {
        return Err(unauthorized());
    }
    let connection = Uuid::new_v4();
    let cancel = CancellationToken::new();
    let mut access = SERVICE.access.lock().await;
    access.expire_controller();
    // authorize() invokes the runtime helper outside this lock. A handoff
    // may have revoked the cookie meanwhile, so recheck before claiming input.
    if !access.cookies.get(&cookie).is_some_and(|t| {
        t.viewer == viewer && t.generation == generation && t.expires > Instant::now()
    }) {
        return Err(unauthorized());
    }
    if access.in_use_by_other(viewer) {
        return Err(error("CONTROLLER_IN_USE"));
    }
    if let Some(old) = &access.controller {
        if old.viewer != viewer {
            return Err(error("CONTROLLER_IN_USE"));
        }
        old.cancel.cancel();
    }
    access.controller = Some(Controller {
        viewer,
        connection,
        cancel: cancel.clone(),
        lease_expires: Instant::now() + Duration::from_secs(30),
    });
    if access
        .handoff
        .as_ref()
        .is_some_and(|h| h.source != viewer && h.target != Some(viewer))
    {
        let handoff = access.handoff.take().unwrap();
        access
            .cancelled
            .insert(handoff.id, Instant::now() + Duration::from_secs(120));
    }
    access.reservation = None;
    drop(access);
    Ok(upgrade
        .protocols(["binary"])
        .max_frame_size(8 * 1024 * 1024)
        .max_message_size(16 * 1024 * 1024)
        .on_upgrade(move |socket| relay(socket, cookie, generation, connection, cancel))
        .into_response())
}
async fn relay(
    down: WebSocket,
    cookie: String,
    generation: String,
    connection: Uuid,
    cancel: CancellationToken,
) {
    let (_, config) = match configured() {
        Ok(c) => c,
        Err(_) => return,
    };
    let url = format!("ws://127.0.0.1:{}/websockify", config.port);
    let mut request = match url.into_client_request() {
        Ok(r) => r,
        Err(_) => return,
    };
    use base64::Engine;
    let authorization =
        base64::engine::general_purpose::STANDARD.encode(format!("viewer:{}", config.password));
    request.headers_mut().insert(
        header::AUTHORIZATION,
        HeaderValue::from_str(&format!("Basic {authorization}")).unwrap(),
    );
    request.headers_mut().insert(
        header::ORIGIN,
        HeaderValue::from_str(&format!("http://127.0.0.1:{}", config.port)).unwrap(),
    );
    request
        .headers_mut()
        .insert("Sec-WebSocket-Protocol", HeaderValue::from_static("binary"));
    if let Ok(Ok((up, _))) =
        tokio::time::timeout(Duration::from_secs(5), connect_async(request)).await
    {
        let (mut ds, mut dr) = down.split();
        let (mut us, mut ur) = up.split();
        let incoming = async {
            while let Some(Ok(message)) = dr.next().await {
                let message = match message {
                    Message::Binary(b) => UpMessage::Binary(b),
                    Message::Text(t) => UpMessage::Text(t),
                    Message::Ping(b) => UpMessage::Ping(b),
                    Message::Pong(b) => UpMessage::Pong(b),
                    Message::Close(_) => break,
                };
                if !matches!(
                    tokio::time::timeout(Duration::from_secs(5), us.send(message)).await,
                    Ok(Ok(()))
                ) {
                    break;
                }
            }
        };
        let outgoing = async {
            while let Some(Ok(message)) = ur.next().await {
                let message = match message {
                    UpMessage::Binary(b) => Message::Binary(b),
                    UpMessage::Text(t) => Message::Text(t),
                    UpMessage::Ping(b) => Message::Ping(b),
                    UpMessage::Pong(b) => Message::Pong(b),
                    _ => break,
                };
                if !matches!(
                    tokio::time::timeout(Duration::from_secs(5), ds.send(message)).await,
                    Ok(Ok(()))
                ) {
                    break;
                }
            }
        };
        let validity = async {
            loop {
                tokio::time::sleep(Duration::from_secs(5)).await;
                let valid_cookie = SERVICE
                    .access
                    .lock()
                    .await
                    .cookies
                    .get(&cookie)
                    .is_some_and(|c| c.expires > Instant::now());
                if !valid_cookie || ready_generation().await.ok().as_ref() != Some(&generation) {
                    break;
                }
                let mut access = SERVICE.access.lock().await;
                if let Some(controller) = access
                    .controller
                    .as_mut()
                    .filter(|c| c.connection == connection)
                {
                    controller.lease_expires = Instant::now() + Duration::from_secs(30);
                } else {
                    break;
                }
            }
        };
        tokio::select! { _=incoming=>{},_=outgoing=>{},_=validity=>{},_=cancel.cancelled()=>{} }
    }
    let mut access = SERVICE.access.lock().await;
    if access
        .controller
        .as_ref()
        .is_some_and(|c| c.connection == connection)
    {
        access.controller = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn transfer_request(viewer: Uuid, handoff: Option<Uuid>, cancel: Option<Uuid>) -> GrantRequest {
        GrantRequest {
            viewer_id: viewer,
            runtime_generation: "current".into(),
            take_control: true,
            handoff_id: handoff,
            cancel_handoff_id: cancel,
            viewer_origin: None,
        }
    }
    fn pending_transfer(access: &mut Access, source: Uuid, id: Uuid) {
        access.handoff = Some(Handoff {
            id,
            source,
            generation: "current".into(),
            target: None,
            expires: Instant::now() + Duration::from_secs(120),
        });
    }
    #[test]
    fn delayed_desktop_cannot_claim_after_phone_recovery() {
        let (source, target, id) = (Uuid::new_v4(), Uuid::new_v4(), Uuid::new_v4());
        let mut access = Access::default();
        pending_transfer(&mut access, source, id);
        access
            .grant_control(&transfer_request(source, None, Some(id)))
            .unwrap();
        assert!(access.cancelled.contains_key(&id));
        assert!(access
            .grant_control(&transfer_request(target, Some(id), None))
            .is_err());
        assert_eq!(access.reservation.as_ref().unwrap().viewer, source);
    }
    #[test]
    fn cancellation_before_transfer_arrives_leaves_a_tombstone() {
        let (source, id) = (Uuid::new_v4(), Uuid::new_v4());
        let mut access = Access::default();
        access
            .grant_control(&transfer_request(source, None, Some(id)))
            .unwrap();
        assert!(access.cancelled.contains_key(&id));
    }
    #[test]
    fn transfer_nonce_is_consumed_once_and_recovery_revokes_target() {
        let (source, target, id) = (Uuid::new_v4(), Uuid::new_v4(), Uuid::new_v4());
        let mut access = Access::default();
        pending_transfer(&mut access, source, id);
        access
            .grant_control(&transfer_request(target, Some(id), None))
            .unwrap();
        assert_eq!(access.handoff.as_ref().unwrap().target, Some(target));
        assert!(access
            .grant_control(&transfer_request(target, Some(id), None))
            .is_err());
        access
            .grant_control(&transfer_request(source, None, Some(id)))
            .unwrap();
        assert!(access.handoff.is_none());
        assert_eq!(access.reservation.as_ref().unwrap().viewer, source);
        assert!(access.in_use_by_other(target));
    }
    #[test]
    fn recovering_old_transfer_cannot_steal_from_a_new_explicit_action() {
        let (source, target, newer, id) = (
            Uuid::new_v4(),
            Uuid::new_v4(),
            Uuid::new_v4(),
            Uuid::new_v4(),
        );
        let mut access = Access::default();
        pending_transfer(&mut access, source, id);
        access
            .grant_control(&transfer_request(target, Some(id), None))
            .unwrap();
        access.take_control(newer);
        assert!(access
            .grant_control(&transfer_request(source, None, Some(id)))
            .is_err());
        assert_eq!(access.reservation.as_ref().unwrap().viewer, newer);
    }
    #[test]
    fn explicit_handoff_revokes_controller_cookies_and_unused_grants() {
        let old = Uuid::new_v4();
        let target = Uuid::new_v4();
        let cancel = CancellationToken::new();
        let mut access = Access::default();
        access.controller = Some(Controller {
            viewer: old,
            connection: Uuid::new_v4(),
            cancel: cancel.clone(),
            lease_expires: Instant::now() + Duration::from_secs(30),
        });
        for entries in [&mut access.cookies, &mut access.grants] {
            entries.insert(
                secret(),
                Ticket {
                    viewer: old,
                    generation: "current".into(),
                    expires: Instant::now() + Duration::from_secs(60),
                    origin: "https://example.test".into(),
                },
            );
        }
        access.take_control(target);
        assert!(cancel.is_cancelled());
        assert!(access.controller.is_none());
        assert!(access.cookies.is_empty() && access.grants.is_empty());
        assert!(access.in_use_by_other(old));
        assert!(!access.in_use_by_other(target));
    }
    #[test]
    fn releasing_desktop_does_not_disconnect_phone() {
        let desktop = Uuid::new_v4();
        let phone = Uuid::new_v4();
        let cancel = CancellationToken::new();
        let mut access = Access::default();
        access.controller = Some(Controller {
            viewer: phone,
            connection: Uuid::new_v4(),
            cancel: cancel.clone(),
            lease_expires: Instant::now() + Duration::from_secs(30),
        });
        for viewer in [desktop, phone] {
            access.cookies.insert(
                secret(),
                Ticket {
                    viewer,
                    generation: "current".into(),
                    expires: Instant::now() + Duration::from_secs(60),
                    origin: "https://example.test".into(),
                },
            );
        }
        access.release_viewer(desktop);
        assert!(!cancel.is_cancelled());
        assert_eq!(access.controller.as_ref().unwrap().viewer, phone);
        assert_eq!(access.cookies.len(), 1);
        assert!(access.cookies.values().all(|t| t.viewer == phone));
    }
    #[test]
    fn unused_handoff_reservation_expires_and_can_be_released() {
        let viewer = Uuid::new_v4();
        let mut access = Access::default();
        access.take_control(viewer);
        access.reservation.as_mut().unwrap().expires = Instant::now() - Duration::from_secs(1);
        access.expire_controller();
        assert!(!access.in_use_by_other(Uuid::new_v4()));
        access.take_control(viewer);
        access.release_viewer(viewer);
        assert!(access.reservation.is_none());
    }
    #[test]
    fn takeover_is_opt_in_and_unknown_request_fields_are_rejected() {
        let request = json!({"viewerId":Uuid::new_v4(),"runtimeGeneration":"current"});
        assert!(
            !serde_json::from_value::<GrantRequest>(request.clone())
                .unwrap()
                .take_control
        );
        let mut explicit = request.clone();
        explicit["takeControl"] = json!(true);
        assert!(
            serde_json::from_value::<GrantRequest>(explicit)
                .unwrap()
                .take_control
        );
        let mut unknown = request;
        unknown["command"] = json!("shell");
        assert!(serde_json::from_value::<GrantRequest>(unknown).is_err());
    }
    #[test]
    fn abandoned_upgrade_cannot_hold_the_controller_forever() {
        let cancellation = CancellationToken::new();
        let mut access = Access::default();
        access.controller = Some(Controller {
            viewer: Uuid::new_v4(),
            connection: Uuid::new_v4(),
            cancel: cancellation.clone(),
            lease_expires: Instant::now() - Duration::from_secs(1),
        });
        access.expire_controller();
        assert!(access.controller.is_none());
        assert!(cancellation.is_cancelled());
    }
    fn config(origin: &str, insecure: bool) -> Config {
        Config {
            session_id: Uuid::new_v4(),
            port: 18000,
            password: "x".repeat(32),
            runner: PathBuf::new(),
            public_origin: origin.into(),
            additional_origins: vec![],
            display_name: "Chrome".into(),
            allow_insecure_loopback: insecure,
        }
    }
    #[test]
    fn secure_origin_only() {
        assert!(valid_origin(&config("https://example.test", false)));
        assert!(valid_origin(&config("https://example.test:4443", false)));
        for origin in [
            "http://example.test",
            "https://user:pass@example.test",
            "https://example.test/path",
            "https://example.test/",
            "https://example.test?token=x",
        ] {
            assert!(!valid_origin(&config(origin, false)));
        }
    }
    #[test]
    fn debug_http_is_loopback_only() {
        assert!(valid_origin(&config("http://127.0.0.1:18000", true)));
        assert!(!valid_origin(&config("http://192.168.0.38:18000", true)));
        assert!(!valid_origin(&config("http://127.0.0.1:18000", false)));
    }
    #[test]
    fn lan_origins_are_explicit_https_aliases_and_old_clients_keep_primary() {
        let mut c = config("https://example.test", false);
        c.additional_origins = vec!["https://lan.example.test:4443".into()];
        assert!(valid_origin(&c));
        assert_eq!(grant_origin(&c, None).unwrap(), c.public_origin);
        assert!(grant_origin(&c, Some("https://lan.example.test:4443")).is_ok());
        assert!(grant_origin(&c, Some("https://untrusted.example.test")).is_err());
        c.additional_origins.push("http://192.168.0.76:4010".into());
        assert!(!valid_origin(&c));
    }
    #[test]
    fn session_ids_are_explicit() {
        let c = config("https://example.test", false);
        assert!(id_matches(&c.session_id.to_string(), &c).is_ok());
        assert!(id_matches(&Uuid::new_v4().to_string(), &c).is_err());
    }
    #[test]
    fn viewer_cookie_has_bounded_shape() {
        let mut h = HeaderMap::new();
        assert!(cookie(&h).is_err());
        h.insert(
            header::COOKIE,
            HeaderValue::from_static("agentshell_browser=invalid"),
        );
        assert!(cookie(&h).is_err());
        let token = secret();
        assert_eq!(token.len(), 64);
        h.insert(
            header::COOKIE,
            HeaderValue::from_str(&format!("other=x; agentshell_browser={token}")).unwrap(),
        );
        assert_eq!(cookie(&h).unwrap(), token);
    }
}
