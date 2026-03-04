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
