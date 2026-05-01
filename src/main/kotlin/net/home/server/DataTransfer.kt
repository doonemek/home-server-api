package net.home.server

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.io.File

import net.home.server.util.respondError

@Serializable
data class UploadSummary(
    val total: Int,
    val warning_count: Int
)

@Serializable
data class UploadResponse(
    val status: String,
    val summary: UploadSummary,
    val detail: List<Map<String, String>>
)

// logger 設定
private val logger = org.slf4j.LoggerFactory.getLogger("DataTransferRoutes")

// ブラックリストの定義
private val blacklistedExtensions = setOf(
    "exe", "msi", "bat", "cmd", "ps1", "vbs",
    "sh", "bin", "app", "jar", "py", "php", "js"
)

// ホワイトリストの定義
private val whitelistedExtensions = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "mp4",
    "m4v", "avi", "wmv", "mov", "webm", "mp3",
    "aac", "wav", "flac", "alac",
)

// イメージ：バリデーション用の拡張関数案
suspend fun ApplicationCall.ensureParameterNotBlank(paramName: String, paramValue: String?): String? {
    if (paramValue.isNullOrBlank()) {
        respondError(
            HttpStatusCode.BadRequest,
            "missing-parameter",
            "The '$paramName' query parameter is required."
        )
        return null
    }
    return paramValue
}

/**
 * データ転送（ダウンロード等）に関するルーティングを定義
 */
fun Route.dataTransferRoutes() {
    // /v1/file
    get("/file") {
        // パラメータの存在チェック
        val relativePath = call.request.queryParameters["path"]
            ?: return@get call.respondError(
                HttpStatusCode.BadRequest,
                "missing-parameter",
                "Required query parameter 'path' is missing."
            )

        // 文字列ベースのディレクトリトラバーサル・形式チェック
        if (relativePath.isBlank() || relativePath.contains("..") || relativePath.startsWith("/")) {
            return@get call.respondError(
                HttpStatusCode.BadRequest,
                "invalid-path-format",
                "The path format is invalid."
            )
        }

        // ファイルオブジェクトの生成
        val baseDir = DirectoryConfig.DATA_ROOT.file
        val requestedFile = File(baseDir, relativePath)

        // OSレベルでの正規化パスを用いた境界チェック
        if (!requestedFile.canonicalPath.startsWith(baseDir.canonicalPath)) {
            return@get call.respondError(
                HttpStatusCode.Forbidden,
                "out-of-bounds-access",
                "Access to the requested location is restricted."
            )
        }

        // ファイルの存在確認とレスポンス
        if (requestedFile.exists() && requestedFile.isFile) {
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName,
                    requestedFile.name // 日本語名の場合エラーになるため今後修正が必要
                ).toString()
            )
            call.respondFile(requestedFile)
        } else {
            return@get call.respondError(
                HttpStatusCode.NotFound,
                "file-not-found",
                "The requested file could not be found."
            )
        }
    }

    // POST: アップロード
    // /v1/file
    post("/file") {
        // API エンドポイントのクエリパラメータを取得
        val relativePath = call.request.queryParameters["path"]

        // クエリパラメータバリデーションチェック
        val validPath = call.ensureParameterNotBlank("path", relativePath) ?: return@post

        // 文字列ベースのディレクトリトラバーサル・形式チェック
        // validation 関数化したいが、現時点ではこのまま利用する
        if (validPath.contains("..") || validPath.startsWith("/")) {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid-path-format",
                "The 'path' parameter contains invalid characters or is an absolute path."
            )
            return@post
        }

        val baseDir = DirectoryConfig.DATA_ROOT.file
        val multipart = call.receiveMultipart(
            formFieldLimit = 5L * 1024L * 1024L * 1024L
        )
        val warnings = mutableListOf<Map<String, String>>()
        var fileCount = 0

        logger.info("Upload request received. Destination: '{}'", relativePath)
        try {
            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        fileCount++
                        val fileName = part.originalFileName ?: "unknown"
                        val extension = fileName.substringAfterLast(".", "").lowercase()
                        val baseName = fileName.substringBeforeLast(".")

                        logger.info("Processing file #{} (extension: '.{}')", fileCount, extension)
                        // ブラックリスト確認：該当すれば即座に全体エラー
                        if (extension in blacklistedExtensions) {
                            logger.warn("Blocked #{}: Prohibited extension '.{}'", fileCount, extension)
                            call.respondError(
                                HttpStatusCode.UnsupportedMediaType,
                                "unsupported-file-type",
                                "The file has a prohibited extension."
                            )
                            part.dispose()
                            throw IllegalStateException("Size Limit Exceeded")
                        }

                        // ホワイトリスト確認
                        if (extension !in whitelistedExtensions) {
                            logger.warn("WARNING #{}: Non-whitelisted file detected", fileCount)
                            warnings.add(mapOf(
                                "file" to fileName,
                                "status" to "WARNING",
                                "reason" to "Non-whitelisted"
                            ))
                        }

                        // 保存先準備
                        val uploadTempDir = File(baseDir, "upload/$relativePath").apply { if (!exists()) mkdirs() }
                        val finalDir = File(baseDir, relativePath).apply { if (!exists()) mkdirs() }
                        val tempFile = File(uploadTempDir, "upload_${fileName}.tmp")

                        try {
                            // 一時保存
                            part.streamProvider().use { input ->
                                tempFile.outputStream().use { output ->
                                    input.copyTo(output, bufferSize = 128 * 1024)
                                }
                            }

                            // サイズ制限(1GB)
                            if (tempFile.length() > 1024 * 1024 * 1024) {
                                tempFile.delete()
                                call.respondError(
                                    HttpStatusCode.PayloadTooLarge,
                                    "file-too-large",
                                    "File exceeds 1GB."
                                )
                                logger.warn("Blocked #{}: File size exceeds limit ('{}' bytes)", fileCount, tempFile.length())
                                throw IllegalStateException("Size Limit Exceeded")
                            }

                            // 重複回避 (100回)
                            var finalFile = File(finalDir, fileName)
                            var counter = 1
                            while (finalFile.exists() && counter <= 100) {
                                val newName = if (extension.isNotEmpty()) "$baseName($counter).$extension" else "$baseName($counter)"
                                finalFile = File(finalDir, newName)
                                counter++
                            }

                            if (counter > 100) {
                                logger.warn("Skipped #{}: Duplicate naming limit reached (100+)", fileCount)
                                warnings.add(mapOf(
                                    "file" to fileName,
                                    "status" to "ERROR",
                                    "reason" to "Duplicate 100 time over"
                                ))
                                tempFile.delete()
                                return@forEachPart
                            }

                            // 移動 (3回リトライ)
                            var moveSuccessful = false
                            for (attempt in 1..3) {
                                if (tempFile.renameTo(finalFile)) {
                                    moveSuccessful = true
                                    break
                                }
                                logger.warn("Rename failed for file #{} (Attempt {}/3). Retrying...", fileCount, attempt)
                                if (attempt < 3) delay(100)
                            }

                            if (!moveSuccessful) {
                                warnings.add(mapOf(
                                    "file" to fileName,
                                    "status" to "ERROR",
                                    "reason" to "Can't move from tmp to request path"
                                ))
                                return@forEachPart
                            }

                            logger.info("File #{} saved", fileCount, fileName)
                        } catch (e: Exception) {
                            if (tempFile.exists()) tempFile.delete()
                            throw e
                        } finally {
                            part.dispose()
                        }
                    }
                        else -> part.dispose()
                }
            }

            if (fileCount == 0) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "no-files",
                    "No files were uploaded."
                )
                return@post
            }

            logger.info("Upload completed. Total: {} files", fileCount)

            // 正常系レスポンスボディ作成
            val response = UploadResponse(
                status = "success",
                summary = UploadSummary(
                    total = fileCount,
                    warning_count = warnings.size
                ),
                detail = warnings
            )

            call.respond(HttpStatusCode.OK, response)

        } catch (e: IllegalStateException) {
            logger.error("Request terminated expectedly: {}", e.message)
            return@post // 既に respondError 済み
        } catch (e: Exception) {
            logger.error("Critical error at file #{}: {}", fileCount, e.message)
            if (!call.response.isCommitted) {
                call.respondError(
                    HttpStatusCode.InternalServerError,
                    "upload-failed",
                    "An unexpected error occurred."
                )
            }
        }
    }
}
