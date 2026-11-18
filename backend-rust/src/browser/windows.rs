use super::{
    configured, error, id_matches, no_store, runtime_command, Access, ApiResult, BrowserError,
    Provider, SERVICE,
};
use axum::{extract::Path, response::IntoResponse, Json};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::time::Duration;
use uuid::Uuid;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(super) struct CatalogRequest {
    viewer_id: Uuid,
    runtime_generation: String,
    #[serde(default)]
    allow_pairing: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ActionRequest {
    viewer_id: Uuid,
    runtime_generation: String,
    request_id: Uuid,
    revision: String,
    #[serde(flatten)]
    command: Command,
}

// A nonnegative Chrome ID fits in a signed 32-bit integer. Special negative
// selectors such as WINDOW_ID_CURRENT must never resolve a stale selection.
#[derive(Deserialize, Serialize)]
#[serde(try_from = "i32")]
struct ChromeId(i32);

impl TryFrom<i32> for ChromeId {
    type Error = &'static str;

    fn try_from(id: i32) -> Result<Self, Self::Error> {
        if id < 0 {
            Err("expected a concrete Chrome ID")
        } else {
            Ok(Self(id))
        }
    }
}

#[derive(Deserialize, Serialize)]
#[serde(
    tag = "action",
    rename_all = "lowercase",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum Command {
    Focus {
        window_id: ChromeId,
        #[serde(skip_serializing_if = "Option::is_none")]
        tab_id: Option<ChromeId>,
    },
    Move {
        window_id: ChromeId,
        tab_id: ChromeId,
        target_window_id: ChromeId,
    },
    Detach {
        window_id: ChromeId,
        tab_id: ChromeId,
    },
    Merge {
        target_window_id: ChromeId,
    },
    Pair {
        window_id: ChromeId,
        tab_id: ChromeId,
        other_tab_id: ChromeId,
    },
    Unpair {
        window_id: ChromeId,
        tab_id: ChromeId,
    },
}

impl ActionRequest {
    fn payload(&self) -> Result<Value, BrowserError> {
        if !opaque_id(&self.revision) || self.request_id.is_nil() {
            return Err(error("WINDOW_ACTION_INVALID"));
        }
        let mut payload =
            serde_json::to_value(&self.command).map_err(|_| error("WINDOW_ACTION_INVALID"))?;
        payload["id"] = json!(self.request_id);
        payload["revision"] = json!(self.revision);
        Ok(payload)
    }
}

fn opaque_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 256
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"-_.:".contains(&b))
}

impl Access {
    fn window_controller(&mut self, viewer: Uuid, generation: &str) -> Result<Uuid, BrowserError> {
        self.expire_controller();
        let controller = self
            .controller
            .as_ref()
            .filter(|c| c.viewer == viewer && !c.cancel.is_cancelled())
            .ok_or_else(|| error("CONTROLLER_CHANGED"))?;
        if controller.generation != generation {
            return Err(error("RUNTIME_CHANGED"));
        }
        Ok(controller.connection)
    }
}

async fn controller(viewer: Uuid, generation: &str) -> Result<Uuid, BrowserError> {
    SERVICE
        .access
        .lock()
        .await
        .window_controller(viewer, generation)
}

fn check_session(id: &str, generation: &str) -> Result<(), BrowserError> {
    let config = &configured()?.1;
    id_matches(id, config)?;
    if config.provider != Provider::Kasmvnc {
        return Err(error("WINDOWS_NOT_SUPPORTED"));
    }
    if !opaque_id(generation) {
        return Err(error("RUNTIME_CHANGED"));
    }
    Ok(())
}

// Project the helper's output onto the phone's contract. Only these fields
// may leave the host, including when a command partially completed.
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct Tab {
    id: ChromeId,
    title: String,
    active: bool,
    pinned: bool,
    group_id: i32,
    split_id: i32,
}

#[derive(Deserialize, Serialize)]
struct Window {
    id: ChromeId,
    focused: bool,
    tabs: Vec<Tab>,
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct Snapshot {
    revision: String,
    split_supported: bool,
    windows: Vec<Window>,
}

#[derive(Deserialize, Serialize)]
struct Reply {
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    snapshot: Option<Snapshot>,
    #[serde(skip_serializing_if = "Option::is_none")]
    code: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    reason: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    moved: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    total: Option<u32>,
}

fn safe_code(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 64
        && value
            .bytes()
            .all(|b| b.is_ascii_uppercase() || b == b'_' || b.is_ascii_digit())
}

impl Reply {
    fn parse(value: Value) -> Result<Self, BrowserError> {
        let reply: Self =
            serde_json::from_value(value).map_err(|_| error("WINDOW_ACTION_FAILED"))?;
        if (reply.ok && reply.snapshot.is_none())
            || (!reply.ok && reply.code.is_none())
            || reply
                .snapshot
                .as_ref()
                .is_some_and(|s| !opaque_id(&s.revision))
            || reply
                .code
                .iter()
                .chain(reply.reason.iter())
                .any(|code| !safe_code(code))
        {
            return Err(error("WINDOW_ACTION_FAILED"));
        }
        Ok(reply)
    }
}

async fn dispatch(viewer: Uuid, generation: &str, connection: Uuid, payload: Value) -> ApiResult {
    if controller(viewer, generation).await? != connection {
        return Err(error("CONTROLLER_CHANGED"));
    }
    let args = payload.to_string();
    let result = runtime_command(
        "windows",
        &["--generation", generation, "--request", &args],
        Duration::from_secs(65),
        1024 * 1024,
    )
    .await?;
    if controller(viewer, generation).await? != connection {
        return Err(error("CONTROLLER_CHANGED"));
    }
    Ok(no_store(Json(Reply::parse(result)?).into_response()))
}

pub(super) async fn catalog(
    Path(id): Path<String>,
    Json(request): Json<CatalogRequest>,
) -> ApiResult {
    check_session(&id, &request.runtime_generation)?;
    let connection = controller(request.viewer_id, &request.runtime_generation).await?;
    let payload =
        json!({"id": Uuid::new_v4(), "action": "list", "allowPairing": request.allow_pairing});
    if request.allow_pairing {
        // Initial pairing can briefly open a tab in the selected profile.
        return exclusive(
            request.viewer_id,
            request.runtime_generation,
            connection,
            payload,
        )
        .await;
    }
    dispatch(
        request.viewer_id,
        &request.runtime_generation,
        connection,
        payload,
    )
    .await
}

pub(super) async fn action(
    Path(id): Path<String>,
    Json(request): Json<ActionRequest>,
) -> ApiResult {
    check_session(&id, &request.runtime_generation)?;
    let payload = request.payload()?;
    let connection = controller(request.viewer_id, &request.runtime_generation).await?;
    exclusive(
        request.viewer_id,
        request.runtime_generation,
        connection,
        payload,
    )
    .await
}

async fn exclusive(
    viewer: Uuid,
    generation: String,
    connection: Uuid,
    payload: Value,
) -> ApiResult {
    // Finish an admitted action even if the HTTP client goes away. This keeps
    // profile handoff, browser stop and controller takeover ordered with a
    // multi-step Chrome move. A retry carries the same extension request ID.
    tokio::spawn(async move {
        let _handoff = SERVICE
            .handoff
            .try_lock()
            .map_err(|_| error("WINDOW_ACTION_BUSY"))?;
        let _runtime = SERVICE
            .runtime
            .try_lock()
            .map_err(|_| error("WINDOW_ACTION_BUSY"))?;
        let _action = SERVICE.window_action.lock().await;
        dispatch(viewer, &generation, connection, payload).await
    })
    .await
    .map_err(|_| error("WINDOW_ACTION_FAILED"))?
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::browser::Controller;
    use std::time::Instant;
    use tokio_util::sync::CancellationToken;

    #[test]
    fn only_typed_commands_and_concrete_selections_reach_runtime() {
        let request = json!({"viewerId":Uuid::new_v4(),"runtimeGeneration":"current",
                             "requestId":Uuid::new_v4(),"revision":"v1","action":"merge","targetWindowId":9});
        let parsed: ActionRequest = serde_json::from_value(request.clone()).unwrap();
        assert_eq!(
            parsed.payload().unwrap(),
            json!({"action":"merge","targetWindowId":9,
                                                    "id":request["requestId"],"revision":"v1"})
        );
        for (key, value) in [
            ("tabId", json!(1)),
            ("url", json!("file:///ignored")),
            ("action", json!("list")),
            ("targetWindowId", json!(-2)),
            ("targetWindowId", json!(4_294_967_296u64)),
        ] {
            let mut invalid = request.clone();
            invalid[key] = value;
            assert!(serde_json::from_value::<ActionRequest>(invalid).is_err());
        }
    }

    #[test]
    fn inventory_requires_the_active_connection_and_runtime() {
        let mut access = Access::default();
        let viewer = Uuid::new_v4();
        let connection = Uuid::new_v4();
        assert!(access.window_controller(viewer, "current").is_err());
        access.controller = Some(Controller {
            viewer,
            connection,
            generation: "current".into(),
            lease_expires: Instant::now() + Duration::from_secs(30),
            cancel: CancellationToken::new(),
        });
        assert_eq!(
            access.window_controller(viewer, "current").unwrap(),
            connection
        );
        assert!(access.window_controller(viewer, "previous").is_err());
        assert!(access.window_controller(Uuid::new_v4(), "current").is_err());
        access.release_viewer(viewer);
        assert!(access.window_controller(viewer, "current").is_err());
    }

    #[test]
    fn only_catalogue_fields_are_returned_even_on_partial_completion() {
        let response = json!({"ok":false,"code":"WINDOW_OPERATION_PARTIAL","moved":1,"total":2,
            "snapshot":{"revision":"1","splitSupported":true,"windows":[{"id":1,"focused":true,"tabs":[
                {"id":2,"title":"Example","active":true,"pinned":false,"groupId":-1,"splitId":-1,
                 "url":"https://ignored.test/"}]}]}});
        let projected = serde_json::to_value(Reply::parse(response).unwrap()).unwrap();
        assert!(projected["snapshot"]["windows"][0]["tabs"][0]
            .get("url")
            .is_none());
        assert_eq!(projected["moved"], 1);
        assert!(Reply::parse(json!({"ok":true})).is_err());
        assert!(Reply::parse(json!({"ok":false,"code":"raw error string"})).is_err());
    }
}
