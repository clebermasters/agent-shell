use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::RwLock;
use tracing::{debug, error, info, warn};

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ChatClearState {
    /// Map of "sessionName:windowIndex" -> timestamp (milliseconds since epoch)
    cleared_at: HashMap<String, i64>,
}

pub struct ChatClearStore {
    path: PathBuf,
    state: Arc<RwLock<ChatClearState>>,
}

impl ChatClearStore {
    pub fn new(data_dir: &PathBuf) -> Self {
        let path = data_dir.join("chat_clear_state.json");
        let state = Self::load_from_file(&path).unwrap_or_default();

        Self {
            path,
            state: Arc::new(RwLock::new(state)),
        }
    }

    fn load_from_file(path: &PathBuf) -> Option<ChatClearState> {
        if !path.exists() {
            return None;
        }

        match fs::read_to_string(path) {
            Ok(content) => match serde_json::from_str(&content) {
                Ok(state) => {
                    debug!("Loaded chat clear state from {:?}", path);
                    Some(state)
                }
                Err(e) => {
                    warn!("Failed to parse chat clear state: {}", e);
                    None
                }
            },
            Err(e) => {
                warn!("Failed to read chat clear state file: {}", e);
                None
            }
        }
    }

    async fn save_to_file(&self, state: &ChatClearState) {
        match serde_json::to_string_pretty(state) {
            Ok(content) => {
                if let Err(e) = fs::write(&self.path, content) {
                    error!("Failed to save chat clear state: {}", e);
                } else {
                    debug!("Saved chat clear state to {:?}", self.path);
                }
            }
            Err(e) => {
                error!("Failed to serialize chat clear state: {}", e);
            }
        }
    }

    pub async fn set_cleared_at(&self, session_name: &str, window_index: u32, timestamp: i64) {
        let key = format!("{}:{}", session_name, window_index);
        let mut state = self.state.write().await;
        state.cleared_at.insert(key, timestamp);
        info!(
            "Set clear timestamp for {}:{} to {}",
            session_name, window_index, timestamp
        );
        self.save_to_file(&state).await;
    }

    pub async fn get_cleared_at(&self, session_name: &str, window_index: u32) -> Option<i64> {
        let key = format!("{}:{}", session_name, window_index);
        let state = self.state.read().await;
        state.cleared_at.get(&key).copied()
    }

    pub async fn clear(&self, session_name: &str, window_index: u32) {
        let key = format!("{}:{}", session_name, window_index);
        let mut state = self.state.write().await;
        state.cleared_at.remove(&key);
        info!("Cleared timestamp for {}:{}", session_name, window_index);
        self.save_to_file(&state).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn make_store() -> (ChatClearStore, TempDir) {
        let dir = TempDir::new().unwrap();
        let store = ChatClearStore::new(&dir.path().to_path_buf());
        (store, dir)
    }

    #[tokio::test]
    async fn test_set_and_get_cleared_at() {
        let (store, _dir) = make_store();
        store.set_cleared_at("sess1", 0, 12345678).await;
        let ts = store.get_cleared_at("sess1", 0).await;
        assert_eq!(ts, Some(12345678));
    }

    #[tokio::test]
    async fn test_get_cleared_at_missing_returns_none() {
        let (store, _dir) = make_store();
        let ts = store.get_cleared_at("nonexistent", 0).await;
        assert_eq!(ts, None);
    }

    #[tokio::test]
    async fn test_clear_removes_entry() {
        let (store, _dir) = make_store();
        store.set_cleared_at("sess1", 0, 999).await;
        store.clear("sess1", 0).await;
        let ts = store.get_cleared_at("sess1", 0).await;
        assert_eq!(ts, None);
    }

    #[tokio::test]
    async fn test_multiple_sessions_independent() {
        let (store, _dir) = make_store();
        store.set_cleared_at("sess1", 0, 100).await;
        store.set_cleared_at("sess2", 0, 200).await;
        assert_eq!(store.get_cleared_at("sess1", 0).await, Some(100));
        assert_eq!(store.get_cleared_at("sess2", 0).await, Some(200));
    }

    #[tokio::test]
    async fn test_multiple_windows_independent() {
        let (store, _dir) = make_store();
        store.set_cleared_at("sess1", 0, 100).await;
        store.set_cleared_at("sess1", 1, 200).await;
        assert_eq!(store.get_cleared_at("sess1", 0).await, Some(100));
        assert_eq!(store.get_cleared_at("sess1", 1).await, Some(200));
    }

    #[tokio::test]
    async fn test_overwrite_cleared_at() {
        let (store, _dir) = make_store();
        store.set_cleared_at("sess1", 0, 100).await;
        store.set_cleared_at("sess1", 0, 999).await;
        assert_eq!(store.get_cleared_at("sess1", 0).await, Some(999));
    }

    #[test]
    fn test_persist_and_reload() {
        let dir = TempDir::new().unwrap();
        {
            let store = ChatClearStore::new(&dir.path().to_path_buf());
            // Can't easily test persistence synchronously without runtime, just test construction
            let _ = store;
        }
        // Reload from same dir
        let _store2 = ChatClearStore::new(&dir.path().to_path_buf());
    }

    #[tokio::test]
    async fn test_persistence_roundtrip_async() {
        let dir = TempDir::new().unwrap();
        {
            let store = ChatClearStore::new(&dir.path().to_path_buf());
            store.set_cleared_at("sess1", 0, 42).await;
        }
        // Create new store from same directory — should load persisted data
        let store2 = ChatClearStore::new(&dir.path().to_path_buf());
        let ts = store2.get_cleared_at("sess1", 0).await;
        assert_eq!(ts, Some(42));
    }

    #[tokio::test]
    async fn test_load_from_corrupt_json() {
        let dir = TempDir::new().unwrap();
        // Write corrupt JSON to the state file
        let path = dir.path().join("chat_clear_state.json");
        std::fs::write(&path, "not valid json {{{").unwrap();
        // Should default to empty state, not panic
        let store = ChatClearStore::new(&dir.path().to_path_buf());
        let ts = store.get_cleared_at("any", 0).await;
        assert_eq!(ts, None);
    }

    // Phase 12: Chat clear store additional tests

    #[tokio::test]
    async fn test_clear_nonexistent_key_is_noop() {
        let (store, _dir) = make_store();
        // Clearing a key that was never set should not panic
        store.clear("never-set", 99).await;
        assert_eq!(store.get_cleared_at("never-set", 99).await, None);
    }

    #[tokio::test]
    async fn test_set_clear_cycle() {
        let (store, _dir) = make_store();
        store.set_cleared_at("sess", 0, 100).await;
        assert_eq!(store.get_cleared_at("sess", 0).await, Some(100));
        store.clear("sess", 0).await;
        assert_eq!(store.get_cleared_at("sess", 0).await, None);
        store.set_cleared_at("sess", 0, 200).await;
        assert_eq!(store.get_cleared_at("sess", 0).await, Some(200));
    }

    #[tokio::test]
    async fn test_multiple_entries_persist_and_reload() {
        let dir = TempDir::new().unwrap();
        {
            let store = ChatClearStore::new(&dir.path().to_path_buf());
            store.set_cleared_at("a", 0, 10).await;
            store.set_cleared_at("b", 1, 20).await;
            store.set_cleared_at("c", 2, 30).await;
        }
        let store2 = ChatClearStore::new(&dir.path().to_path_buf());
        assert_eq!(store2.get_cleared_at("a", 0).await, Some(10));
        assert_eq!(store2.get_cleared_at("b", 1).await, Some(20));
        assert_eq!(store2.get_cleared_at("c", 2).await, Some(30));
    }

    #[tokio::test]
    async fn test_key_format_verification() {
        let (store, _dir) = make_store();
        store.set_cleared_at("my-session", 3, 555).await;
        // Verify internal key format is "session:window"
        let state = store.state.read().await;
        assert!(state.cleared_at.contains_key("my-session:3"));
    }
}
