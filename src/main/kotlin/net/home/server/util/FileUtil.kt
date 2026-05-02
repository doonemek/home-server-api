package net.home.server.util

import java.io.File

// ブラックリストの定義
val blacklistedExtensions = setOf(
    "exe", "msi", "bat", "cmd", "ps1", "vbs",
    "sh", "bin", "app", "jar", "py", "php", "js"
)

// ホワイトリストの定義
val whitelistedExtensions = setOf(
    "jpg", "jpeg", "png", "gif", "webp",
    "mp4", "m4v", "avi", "wmv", "mov", "webm",
    "mp3", "aac", "wav", "flac", "alac",
    "pdf", "doc", "docx", "xlsx", "pptx",
    "txt", "json"
)

// Windows 予約語の定義
val reservedNames = setOf(
    "CON", "PRN", "AUX", "NUL",
    "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
    "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
)

// 上限ファイルサイズ
const val MAX_FILE_SIZE = 1024L * 1024L * 1024L
const val REQUEST_MAX_FILE_SIZE = 5L * 1024L * 1024L * 1024L
const val COPY_BUFFER_SIZE = 128 * 1024

// リネーム上限
const val MAX_RENAME_ATTEMPTS = 100

/**
 * パスやファイル名が安全かどうかを判定する
 */
fun String?.isValidName(): Boolean {
    if (this.isNullOrBlank()) return false

    // パス区切り、トラバーサル記号、ドット開始（隠しファイル）の禁止
    if (this.contains("/") || this.contains("\\") || this.contains("..") || this.startsWith(".")) {
        return false
    }

    // Windows予約名のチェック
    if (reservedNames.contains(this.uppercase().substringBefore("."))) {
        return false
    }

    // OSで使用禁止されている特殊記号
    val invalidChars = Regex("[<>:\"|?*\\x00-\\x1F]")
    if (invalidChars.containsMatchIn(this)) return false

    // 長すぎるファイル名の制限 (OS制限を考慮し255文字以下)
    if (this.length > 255) return false

    return true
}

/**
 * 重複しないファイル名を生成するロジック
 */
fun generateUniqueFile(directory: File, baseName: String, extension: String): File? {
    val suffix = if (extension.isNotEmpty()) ".$extension" else ""

    var finalFile = File(directory, "$baseName$suffix")
    if (!finalFile.exists()) return finalFile

    for (counter in 1..MAX_RENAME_ATTEMPTS) {
        finalFile = File(directory, "$baseName($counter)$suffix")
        if (!finalFile.exists()) return finalFile
    }

    return null
}
