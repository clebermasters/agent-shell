package com.agentshell.feature.chat

import android.net.Uri
import java.nio.file.Paths
import kotlin.math.min

private val SERVER_PATH_TRAILING_CHARS = setOf(
    '.', ',', ';', ':', ')', ']', '}', '>', '"', '\'', '`', '!', '?', '/',
)

private val SERVER_PATH_ENCLOSING_CHARS = mapOf(
    '"' to '"',
    '\'' to '\'',
    '`' to '`',
    '(' to ')',
    '[' to ']',
    '{' to '}',
    '<' to '>',
)

private val SERVER_PATH_EXTRACTOR = Regex(
    "(?<![A-Za-z0-9_./:+-])(file://\\S+|(?<!/)\\/(?!/)\\S+|~/(?:\\S+)|(?:\\.{1,2}/|[A-Za-z0-9._+-]+/)[^\\s`\"'\\)\\]\\}>!?;,]+)",
    RegexOption.IGNORE_CASE,
)
private val SERVER_LINE_SUFFIX = Regex(":\\d+(?::\\d+)?$")
private const val LOCAL_HOME_PREFIX = "~/"

private val TEXT_FILE_EXTENSIONS = setOf(
    "md", "markdown", "txt", "text", "json", "yaml", "yml", "toml",
    "log", "csv", "tsv", "ini", "cfg", "conf", "env", "properties",
    "xml", "html", "htm", "css", "scss", "sass", "less", "js", "jsx",
    "ts", "tsx", "java", "kt", "kts", "gradle", "py", "rb", "go", "rs",
    "cpp", "c", "h", "cs", "php", "swift", "scala", "kotlin", "sql",
    "sh", "bash", "zsh", "fish", "bat", "ps1", "graphql", "makefile",
    "dockerfile", "gradlew", "gitignore", "gitmodules", "editorconfig", "properties",
)

private val TEXT_FILE_BASENAMES = setOf(
    "readme", "dockerfile", "makefile", "license", "changelog", "rakefile",
)

private val BINARY_FILE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "mp3", "wav", "ogg",
    "m4a", "aac", "flac", "mp4", "mov", "webm",
)

private fun String.stripServerPathSurroundingAndSuffix(): String {
    var path = trim()
    var changed: Boolean
    do {
        changed = false

        val match = SERVER_LINE_SUFFIX.find(path)
        if (match != null && match.value.count { it == ':' } <= 2) {
            path = path.removeRange(match.range)
            changed = true
        }

        while (path.length >= 2) {
            val first = path.first()
            val last = path.last()
            val expected = SERVER_PATH_ENCLOSING_CHARS[first] ?: break
            if (last != expected) break
            path = path.substring(1, path.length - 1)
            changed = true
        }

        while (path.isNotEmpty() && SERVER_PATH_TRAILING_CHARS.contains(path.last())) {
            path = path.dropLast(1)
            changed = true
        }
    } while (changed && path.isNotEmpty())

    return path
}

private fun looksLikeRelativePath(candidate: String): Boolean {
    if (candidate.isBlank()) return false
    if (candidate.startsWith("file://") || candidate.startsWith("/") || candidate.startsWith(LOCAL_HOME_PREFIX)) {
        return false
    }
    if (candidate.startsWith("./") || candidate.startsWith("../")) return true
    if (candidate.contains('/')) return true

    val base = candidate.substringAfterLast('/').substringBefore('?').lowercase()
    val extension = base.substringAfterLast('.', "")
    return extension in TEXT_FILE_EXTENSIONS || base in TEXT_FILE_BASENAMES
}

private fun normalizeServerPath(path: String): String {
    val decoded = Uri.decode(path) ?: path
    return if (decoded.startsWith("/") || decoded.startsWith(LOCAL_HOME_PREFIX)) decoded else path
}

private fun resolveRelativeServerPath(path: String, basePath: String): String? {
    val normalizedBase = basePath.trim()
    if (normalizedBase.isEmpty()) return null
    return runCatching {
        Paths.get(normalizedBase).resolve(path).normalize().toString()
    }.getOrNull()
}

private fun isRemoteUrl(raw: String): Boolean {
    return raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("ftp://") || raw.startsWith("mailto:")
}

fun parseServerPathFromLink(rawUrl: String, basePath: String? = null): String? {
    val candidate = rawUrl.stripServerPathSurroundingAndSuffix()
    if (candidate.isEmpty() || isRemoteUrl(candidate)) return null

    if (candidate.startsWith("file://")) {
        val parsed = Uri.parse(candidate)
        val path = parsed.path
        if (!path.isNullOrEmpty()) {
            return normalizeServerPath(path)
        }
        return null
    }

    if (!candidate.startsWith("/") && !candidate.startsWith(LOCAL_HOME_PREFIX)) {
        if (!basePath.isNullOrBlank() && looksLikeRelativePath(candidate)) {
            return resolveRelativeServerPath(candidate, basePath)
        }
        return null
    }

    return normalizeServerPath(candidate)
}

fun extractServerPathFromText(
    text: String,
    offset: Int,
    windowRadius: Int = 192,
    basePath: String? = null,
): String? {
    if (text.isBlank()) return null
    if (offset !in text.indices) return null

    val normalizedOffset = offset.coerceIn(text.indices)
    val windowStart = maxOf(0, normalizedOffset - windowRadius)
    val windowEnd = min(text.length, normalizedOffset + windowRadius + 1)
    if (windowStart >= windowEnd) return null

    val windowText = text.substring(windowStart, windowEnd)
    val relOffset = normalizedOffset - windowStart

    var bestMatch: MatchResult? = null
    var bestDistance = Int.MAX_VALUE

    for (match in SERVER_PATH_EXTRACTOR.findAll(windowText)) {
        val start = match.range.first
        val end = match.range.last + 1

        val path = parseServerPathFromLink(match.value, basePath = basePath) ?: continue

        if (relOffset in start..end) {
            return path
        }

        val distance = when {
            relOffset < start -> start - relOffset
            relOffset > end -> relOffset - end
            else -> 0
        }

        if (distance < bestDistance) {
            bestDistance = distance
            bestMatch = match
        }
    }

    return bestMatch?.let { parseServerPathFromLink(it.value, basePath = basePath) }
}

fun isMediaServerPath(path: String): Boolean {
    val normalized = path.substringAfterLast('/').substringBefore('?').lowercase()
    val extension = normalized.substringAfterLast('.', "")
    return extension in BINARY_FILE_EXTENSIONS
}

fun isTextLikeServerPath(path: String): Boolean {
    val base = path.substringAfterLast('/').lowercase()
    val extension = base.substringAfterLast('.', "")

    if (extension.isNotEmpty()) {
        return extension in TEXT_FILE_EXTENSIONS || extension !in BINARY_FILE_EXTENSIONS
    }

    return base in TEXT_FILE_BASENAMES || base.startsWith(".gitignore")
}
