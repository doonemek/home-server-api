package net.home.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.home.server.util.respondError
import java.io.File

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
                    requestedFile.name
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
}
