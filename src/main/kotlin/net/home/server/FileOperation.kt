package net.home.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Files
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import kotlinx.serialization.Serializable

import net.home.server.util.*

// JSON用のデータ型
@Serializable
data class ContentInfo(
    val name: String,
    val path: String,
    val type: String,
    val size: Long?,
    val createdAt: String,
    val updatedAt: String,
    // val author: String?, 現在仕様が決まっていないため、TODO
    val children: List<ContentInfo>? = null
)

private val logger = org.slf4j.LoggerFactory.getLogger("FileOperation")

// 共通のフォーマッタ (ISO 8601 / UTC)
private val dateFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    .withZone(ZoneId.of("UTC"))

// 再帰的にファイル構造を構築する関数
fun getFileTree(file: File, baseDir: File, currentDepth: Int, maxDepth: Int): ContentInfo {
    val isDirectory = file.isDirectory
    val type = if (isDirectory) "directory" else "file"

    // 相対パスの計算
    val relativePath = file.relativeTo(baseDir).path.replace("\\", "/")

    // ファイル属性（作成日・更新日）の取得
    val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
    val createdAt = dateFormatter.format(attributes.creationTime().toInstant())
    val updatedAt = dateFormatter.format(attributes.lastModifiedTime().toInstant())

    // 再帰処理
    val children = if (isDirectory && currentDepth < maxDepth) {
        file.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) // ファイル優先 > 名前順
            ?.map { getFileTree(it, baseDir, currentDepth + 1, maxDepth) }
    } else {
        null
    }

    return ContentInfo(
        name = file.name,
        path = relativePath,
        type = type,
        size = if (isDirectory) null else file.length(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        // author = "admin",
        children = children
    )
}

fun Route.fileOperationRoutes() {
    // /v1/contents
    get("/contents") {
        // path のバリデーションチェック
        val relativePath = call.request.queryParameters["path"]

        // DataTransfer.kt に存在する関数だが、リファクタ時に外部出ししたいが今はしない。
        val validPath = call.ensureParameterNotBlank("path", relativePath) ?: return@get
        if (!validPath.split("/").all { it.isValidName() }) {
            logger.error("Blocked invalid characters: '{}'", validPath)
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid-path-format",
                "The 'path' contains invalid characters."
            )
            return@get
        }

        // 取得対象ファイル・ディレクトリ一覧
        val baseDir = DirectoryConfig.DATA_ROOT.file
        val requestedDir = File(baseDir, relativePath)

        // ディレクトリチェック
        if (!requestedDir.exists() || !requestedDir.isDirectory) {
            logger.error("This `path` does not exist: '{}'", requestedDir)
            call.respondError(
                HttpStatusCode.NotFound,
                "not-exist-directory",
                "This 'path' does not exist."
            )
            return@get
        }

        // 再帰的に取得するかのパラメータ取得
        val isRecursive = call.request.queryParameters["recursive"]?.toBoolean() ?: false
        val maxDepth = if (isRecursive) 5 else 0

        // 再帰的に取得するかどうかを判断したい
        val tree = requestedDir.listFiles()?.map {
            getFileTree(it, baseDir, 1, maxDepth)
        } ?: emptyList()

        call.respond(HttpStatusCode.OK, tree)

        return@get
    }
}
