use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use std::path::PathBuf;
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
        let extension = mime_type
            .split('/')
            .nth(1)
            .unwrap_or("bin")
            .split(';')
            .next()
            .unwrap_or("bin");

        let file_path = self.storage_dir.join(format!("{}.{}", id, extension));

        let decoded = BASE64
            .decode(data)
            .map_err(|e| format!("Failed to decode base64: {}", e))?;

        std::fs::write(&file_path, decoded).map_err(|e| format!("Failed to write file: {}", e))?;

        Ok(id)
    }

    pub fn get_path(&self, id: &str) -> Option<PathBuf> {
        // Try common extensions
        let extensions = [
            "png", "jpg", "jpeg", "gif", "webp", "pdf", "mp3", "wav", "ogg", "html", "txt", "bin",
        ];

        for ext in extensions {
            let path = self.storage_dir.join(format!("{}.{}", id, ext));
            if path.exists() {
                return Some(path);
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
                    "txt" => "text/plain",
                    _ => "application/octet-stream",
                }
                .to_string()
            })
        })
    }
}
