use super::Conversation;
use anyhow::{ensure, Result};
use std::{
    fs,
    io::ErrorKind,
    path::{Path, PathBuf},
};

/// The CLI holds presence/<conversation UUID>.lock open while that conversation
/// is active. Old lock files remain on disk after the CLI releases them.
pub(super) fn from_open_files(root: &Path, files: &[PathBuf]) -> Result<Option<Conversation>> {
    let root = match fs::canonicalize(root) {
        Ok(root) => root,
        Err(error) if error.kind() == ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    let presence = root.join("presence");
    let mut ids = files.iter().filter_map(|path| {
        if path.parent()? != presence || path.extension()? != "lock" {
            return None;
        }
        let id = path.file_stem()?.to_str()?;
        let parsed = uuid::Uuid::parse_str(id).ok()?;
        (parsed.hyphenated().to_string() == id).then_some(id)
    });
    let Some(id) = ids.next() else {
        return Ok(None);
    };
    if ids.any(|other| other != id) {
        return Ok(None);
    }
    let transcript = root
        .join("brain")
        .join(id)
        .join(".system_generated/logs/transcript_full.jsonl");
    let path = match fs::canonicalize(&transcript) {
        Ok(path) => {
            ensure!(
                path == transcript && path.is_file(),
                "Invalid Antigravity transcript path"
            );
            path
        }
        // The CLI can acquire the lock before writing its first transcript.
        Err(error) if error.kind() == ErrorKind::NotFound => PathBuf::new(),
        Err(error) => return Err(error.into()),
    };
    Ok(Some(Conversation {
        id: id.into(),
        tool: "agy".into(),
        title: "Antigravity conversation".into(),
        updated_at: 0,
        path,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_the_process_held_uuid_lock_selects_the_full_transcript() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().canonicalize().unwrap();
        let first = "2e55860e-0641-4dd5-8457-ce1bcde13eea";
        let next = "8f891c18-ce94-44e5-8685-cd310577bba1";
        let presence = root.join("presence");
        fs::create_dir(&presence).unwrap();
        let first_lock = presence.join(format!("{first}.lock"));
        let next_lock = presence.join(format!("{next}.lock"));
        fs::write(&first_lock, "").unwrap();
        fs::write(&next_lock, "").unwrap();
        let paths = [
            root.join("elsewhere").join(format!("{first}.lock")),
            presence.join("invalid.lock"),
        ];
        assert!(from_open_files(&root, &paths).unwrap().is_none());

        let active = std::slice::from_ref(&first_lock);
        let pending = from_open_files(&root, active).unwrap().unwrap();
        assert_eq!(pending.id, first);
        assert!(pending.path.as_os_str().is_empty());
        let transcript = root
            .join("brain")
            .join(first)
            .join(".system_generated/logs/transcript_full.jsonl");
        fs::create_dir_all(transcript.parent().unwrap()).unwrap();
        fs::write(&transcript, "").unwrap();
        assert_eq!(
            from_open_files(&root, active).unwrap().unwrap().path,
            transcript
        );
        assert!(from_open_files(&root, &[first_lock, next_lock.clone()])
            .unwrap()
            .is_none());
        assert_eq!(
            from_open_files(&root, &[next_lock]).unwrap().unwrap().id,
            next
        );
    }
}
