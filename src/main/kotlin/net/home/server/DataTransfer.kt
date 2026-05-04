package net.home.server

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.delay
import java.io.File
import java.text.Normalizer

import net.home.server.model.UploadResponse
import net.home.server.model.UploadSummary
import net.home.server.util.*

// logger 設定
private val logger = org.slf4j.LoggerFactory.getLogger("DataTransferRoutes")

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
    // /v1/files
    post("/files") {
        // API エンドポイントのクエリパラメータを取得
        val relativePath = call.request.queryParameters["path"]

        // クエリパラメータバリデーションチェック
        val validPath = call.ensureParameterNotBlank("path", relativePath) ?: return@post
        val isPathSafe = validPath.split("/").all { it.isValidName() }
        if (!isPathSafe) {
            logger.warn("Blocked invalid characters: '{}'", validPath)
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid-path-format",
                "The 'path' contains invalid characters."
            )
            return@post
        }

        val baseDir = DirectoryConfig.DATA_ROOT.file
        val multipart = call.receiveMultipart(
            formFieldLimit = REQUEST_MAX_FILE_SIZE
        )
        val warnings = mutableListOf<Map<String, String>>()
        var fileCount = 0

        logger.info("Upload request received. Destination: '{}'", relativePath)
        try {
            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        fileCount++

                        // NFC での normalize 処理して文字化け対応
                        val rawFileName = part.originalFileName ?: "unknown"
                        val fileName = Normalizer.normalize(rawFileName, Normalizer.Form.NFC)

                        // XXX.yyy をファイル名: XXX, 拡張子: yyy に分割する
                        val extension = fileName.substringAfterLast(".", "").lowercase()
                        val baseName = fileName.substringBeforeLast(".")

                        logger.info("Processing file #{} (extension: '.{}')", fileCount, extension)
                        // ファイル名不正チェック
                        if (!baseName.isValidName()) {
                            logger.warn("Blocked #{}:  '{}'", fileCount, fileName)
                            call.respondError(
                                HttpStatusCode.BadRequest,
                                "unsupported-file-name",
                                "The file name contains invalid characters."
                            )
                            part.dispose()
                            throw IllegalStateException("Prohibited file name detected")
                        }

                        // ブラックリスト確認：該当すれば即座に全体エラー
                        if (extension in blacklistedExtensions) {
                            logger.warn("Blocked #{}: Prohibited extension '.{}'", fileCount, extension)
                            call.respondError(
                                HttpStatusCode.UnsupportedMediaType,
                                "unsupported-file-type",
                                "The file has a prohibited extension."
                            )
                            part.dispose()
                            // SECURITY_VIOLATION のようなプレフィックスがあると検索しやすい
                            throw IllegalStateException("Blacklisted extension detected")
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
                            part.provider().toInputStream().use { input ->
                                tempFile.outputStream().use { output ->
                                    input.copyTo(output, bufferSize = COPY_BUFFER_SIZE)
                                }
                            }

                            // サイズ制限(1GB)
                            if (tempFile.length() > MAX_FILE_SIZE) {
                                tempFile.delete()
                                call.respondError(
                                    HttpStatusCode.PayloadTooLarge,
                                    "file-too-large",
                                    "File exceeds 1GB."
                                )
                                logger.warn("Blocked #{}: File size exceeds limit ('{}' bytes)", fileCount, tempFile.length())
                                throw IllegalStateException("Size Limit Exceeded")
                            }

                            // 重複回避
                            val finalFile = generateUniqueFile(finalDir, baseName, extension)
                            if (finalFile == null) {
                                logger.warn("Skipped #{}: Duplicate naming limit reached (100+)", fileCount)
                                warnings.add(mapOf(
                                    "file" to fileName,
                                    "status" to "ERROR",
                                    "reason" to "Duplicate limit exceeded"
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

            call.respond(HttpStatusCode.Created , response)

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
