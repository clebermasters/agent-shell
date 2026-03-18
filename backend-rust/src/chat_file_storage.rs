use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use std::path::{Path, PathBuf};
use uuid::Uuid;

pub struct ChatFileStorage {
    storage_dir: PathBuf,
}

impl ChatFileStorage {
    pub fn new(base_dir: PathBuf) -> Self {
        let storage_dir = base_dir.join("chat_files");
        std::fs::create_dir_all(&storage_dir).ok();
        Self { storage_dir }
    }

    pub fn save_file(&self, data: &str, filename: &str, mime_type: &str) -> Result<String, String> {
        let id = Uuid::new_v4().to_string();
        let extension = extension_from_filename(filename)
            .or_else(|| extension_from_mime_type(mime_type))
            .unwrap_or_else(|| "bin".to_string());

        let file_path = self.storage_dir.join(format!("{}.{}", id, extension));

        let decoded = BASE64
            .decode(data)
            .map_err(|e| format!("Failed to decode base64: {}", e))?;

        std::fs::write(&file_path, decoded).map_err(|e| format!("Failed to write file: {}", e))?;

        Ok(id)
    }

    pub fn save_file_to_directory(
        &self,
        data: &str,
        filename: &str,
        mime_type: &str,
        target_dir: &Path,
    ) -> Result<PathBuf, String> {
        std::fs::create_dir_all(target_dir)
            .map_err(|e| format!("Failed to create directory: {}", e))?;

        let _extension = extension_from_filename(filename)
            .or_else(|| extension_from_mime_type(mime_type))
            .unwrap_or_else(|| "bin".to_string());

        let safe_filename = sanitize_filename(filename);
        let target_path = target_dir.join(format!("{}_{}", Uuid::new_v4(), safe_filename));

        let decoded = BASE64
            .decode(data)
            .map_err(|e| format!("Failed to decode base64: {}", e))?;

        std::fs::write(&target_path, decoded)
            .map_err(|e| format!("Failed to write file: {}", e))?;

        Ok(target_path)
    }

    pub fn get_path(&self, id: &str) -> Option<PathBuf> {
        let prefix = format!("{id}.");
        let entries = std::fs::read_dir(&self.storage_dir).ok()?;

        for entry in entries.flatten() {
            let path = entry.path();
            if !path.is_file() {
                continue;
            }

            if let Some(name) = path.file_name().and_then(|n| n.to_str()) {
                if name.starts_with(&prefix) {
                    return Some(path);
                }
            }
        }

        None
    }

    pub fn get_file_data(&self, id: &str) -> Option<Vec<u8>> {
        self.get_path(id).and_then(|p| std::fs::read(p).ok())
    }

    pub fn get_mime_type(&self, id: &str) -> Option<String> {
        self.get_path(id).and_then(|p| {
            p.extension().and_then(|e| e.to_str()).map(|e| {
                match e {
                    "png" => "image/png",
                    "jpg" | "jpeg" => "image/jpeg",
                    "gif" => "image/gif",
                    "webp" => "image/webp",
                    "pdf" => "application/pdf",
                    "mp3" => "audio/mpeg",
                    "wav" => "audio/wav",
                    "ogg" => "audio/ogg",
                    "html" => "text/html",
                    "htm" => "text/html",
                    "txt" => "text/plain",
                    "md" | "markdown" => "text/markdown",
                    "json" => "application/json",
                    "csv" => "text/csv",
                    "xml" => "application/xml",
                    "yaml" | "yml" => "application/x-yaml",
                    "zip" => "application/zip",
                    "gz" => "application/gzip",
                    "tar" => "application/x-tar",
                    "7z" => "application/x-7z-compressed",
                    "doc" => "application/msword",
                    "docx" => {
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    }
                    "xls" => "application/vnd.ms-excel",
                    "xlsx" => "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "ppt" => "application/vnd.ms-powerpoint",
                    "pptx" => {
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                    }
                    _ => "application/octet-stream",
                }
                .to_string()
            })
        })
    }
}

fn extension_from_filename(filename: &str) -> Option<String> {
    let path = Path::new(filename);
    let ext = path.extension()?.to_str()?.trim().to_ascii_lowercase();
    if ext.is_empty() {
        return None;
    }

    if ext.chars().all(|c| c.is_ascii_alphanumeric()) {
        Some(ext)
    } else {
        None
    }
}

fn extension_from_mime_type(mime_type: &str) -> Option<String> {
    let normalized = mime_type
        .split(';')
        .next()
        .unwrap_or(mime_type)
        .trim()
        .to_ascii_lowercase();

    let mapped = match normalized.as_str() {
        "text/plain" => "txt",
        "text/markdown" => "md",
        "application/json" => "json",
        "text/csv" => "csv",
        "application/xml" | "text/xml" => "xml",
        "application/x-yaml" | "text/yaml" => "yaml",
        "application/pdf" => "pdf",
        "application/zip" => "zip",
        "application/gzip" => "gz",
        "application/x-tar" => "tar",
        "audio/mpeg" => "mp3",
        "audio/wav" | "audio/x-wav" => "wav",
        "audio/ogg" => "ogg",
        "image/jpeg" => "jpg",
        "image/png" => "png",
        "image/gif" => "gif",
        "image/webp" => "webp",
        _ => {
            let ext = normalized
                .split('/')
                .nth(1)
                .unwrap_or("bin")
                .split('+')
                .next()
                .unwrap_or("bin")
                .trim_start_matches("x-")
                .trim();

            if ext.is_empty() {
                "bin"
            } else {
                ext
            }
        }
    };

    Some(mapped.to_string())
}

fn sanitize_filename(filename: &str) -> String {
    let path = Path::new(filename);
    path.file_name()
        .and_then(|n| n.to_str())
        .map(|s| {
            s.chars()
                .map(|c| {
                    if c.is_alphanumeric() || c == '.' || c == '-' || c == '_' {
                        c
                    } else {
                        '_'
                    }
                })
                .collect()
        })
        .unwrap_or_else(|| "file".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
    use tempfile::TempDir;

    fn make_storage() -> (ChatFileStorage, TempDir) {
        let dir = TempDir::new().unwrap();
        let storage = ChatFileStorage::new(dir.path().to_path_buf());
        (storage, dir)
    }

    fn encode(data: &[u8]) -> String {
        BASE64.encode(data)
    }

    #[test]
    fn test_save_and_get_file() {
        let (storage, _dir) = make_storage();
        let data = encode(b"hello world");
        let id = storage.save_file(&data, "test.txt", "text/plain").unwrap();
        let retrieved = storage.get_file_data(&id).unwrap();
        assert_eq!(retrieved, b"hello world");
    }

    #[test]
    fn test_get_path() {
        let (storage, _dir) = make_storage();
        let data = encode(b"content");
        let id = storage.save_file(&data, "file.txt", "text/plain").unwrap();
        let path = storage.get_path(&id);
        assert!(path.is_some());
        assert!(path.unwrap().exists());
    }

    #[test]
    fn test_get_path_missing_returns_none() {
        let (storage, _dir) = make_storage();
        assert!(storage.get_path("nonexistent-id").is_none());
    }

    #[test]
    fn test_get_file_data_missing_returns_none() {
        let (storage, _dir) = make_storage();
        assert!(storage.get_file_data("missing-id").is_none());
    }

    #[test]
    fn test_extension_from_filename() {
        assert_eq!(extension_from_filename("photo.jpg"), Some("jpg".to_string()));
        assert_eq!(extension_from_filename("doc.PDF"), Some("pdf".to_string()));
        assert_eq!(extension_from_filename("noext"), None);
        assert_eq!(extension_from_filename(".hidden"), None);
    }

    #[test]
    fn test_extension_from_mime_type() {
        assert_eq!(extension_from_mime_type("image/png"), Some("png".to_string()));
        assert_eq!(extension_from_mime_type("image/jpeg"), Some("jpg".to_string()));
        assert_eq!(extension_from_mime_type("application/json"), Some("json".to_string()));
        assert_eq!(extension_from_mime_type("text/plain"), Some("txt".to_string()));
        assert_eq!(extension_from_mime_type("audio/mpeg"), Some("mp3".to_string()));
        assert_eq!(extension_from_mime_type("application/pdf"), Some("pdf".to_string()));
        assert_eq!(extension_from_mime_type("text/markdown"), Some("md".to_string()));
        assert_eq!(extension_from_mime_type("application/zip"), Some("zip".to_string()));
        assert_eq!(extension_from_mime_type("application/gzip"), Some("gz".to_string()));
        assert_eq!(extension_from_mime_type("audio/wav"), Some("wav".to_string()));
        assert_eq!(extension_from_mime_type("audio/ogg"), Some("ogg".to_string()));
        assert_eq!(extension_from_mime_type("image/gif"), Some("gif".to_string()));
        assert_eq!(extension_from_mime_type("image/webp"), Some("webp".to_string()));
    }

    #[test]
    fn test_extension_from_mime_type_with_params() {
        // MIME types can have ;charset=... suffix
        assert_eq!(extension_from_mime_type("text/plain; charset=utf-8"), Some("txt".to_string()));
    }

    #[test]
    fn test_sanitize_filename() {
        assert_eq!(sanitize_filename("hello world.txt"), "hello_world.txt");
        assert_eq!(sanitize_filename("file/with/slash.txt"), "slash.txt"); // path.file_name()
        assert_eq!(sanitize_filename("normal_file-1.txt"), "normal_file-1.txt");
    }

    #[test]
    fn test_save_file_extension_from_mime() {
        let (storage, _dir) = make_storage();
        let data = encode(b"png data");
        let id = storage.save_file(&data, "noext", "image/png").unwrap();
        let path = storage.get_path(&id).unwrap();
        assert_eq!(path.extension().and_then(|e| e.to_str()), Some("png"));
    }

    #[test]
    fn test_get_mime_type() {
        let (storage, _dir) = make_storage();
        let data = encode(b"png content");
        let id = storage.save_file(&data, "image.png", "image/png").unwrap();
        let mime = storage.get_mime_type(&id).unwrap();
        assert_eq!(mime, "image/png");
    }

    #[test]
    fn test_get_mime_type_various() {
        let (storage, _dir) = make_storage();
        let cases: &[(&str, &str)] = &[
            ("image/jpeg", "f.jpg"),
            ("image/gif", "f.gif"),
            ("image/webp", "f.webp"),
            ("application/pdf", "f.pdf"),
            ("audio/mpeg", "f.mp3"),
            ("audio/wav", "f.wav"),
            ("audio/ogg", "f.ogg"),
            ("text/html", "f.html"),
            ("text/plain", "f.txt"),
            ("application/json", "f.json"),
            ("text/csv", "f.csv"),
            ("application/xml", "f.xml"),
            ("application/zip", "f.zip"),
            ("application/gzip", "f.gz"),
            ("application/x-tar", "f.tar"),
            ("application/x-7z-compressed", "f.7z"),
            ("application/msword", "f.doc"),
        ];
        for (expected_mime, filename) in cases {
            let id = storage.save_file(&encode(b"x"), filename, *expected_mime).unwrap();
            let mime = storage.get_mime_type(&id).unwrap();
            assert_eq!(&mime, expected_mime, "Failed for {}", filename);
        }
    }

    #[test]
    fn test_save_file_to_directory() {
        let (storage, dir) = make_storage();
        let target = dir.path().join("uploads");
        let data = encode(b"file content");
        let path = storage.save_file_to_directory(&data, "test.txt", "text/plain", &target).unwrap();
        assert!(path.exists());
        assert_eq!(std::fs::read(&path).unwrap(), b"file content");
    }

    #[test]
    fn test_save_file_invalid_base64() {
        let (storage, _dir) = make_storage();
        let result = storage.save_file("not-valid-base64!!!", "file.txt", "text/plain");
        assert!(result.is_err());
    }
}
