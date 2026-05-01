package net.home.server

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.home.server.util.respondError
import java.io.File

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

    }
}
