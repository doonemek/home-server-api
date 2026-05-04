package net.home.server.util

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*

import net.home.server.model.ApiErrorResponse

/**
 * RFC 9457 準拠のエラーレスポンスを返却する拡張関数
 * @param status HTTPステータスコード
 * @param typeStr typeフィールドに使用する識別子（URLの末尾になる文言）
 * @param detail エラーの具体的な説明
 */
suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    typeStr: String,
    detail: String
) {
    val baseUrl = application.environment.config
        .propertyOrNull("server.baseUrl")?.getString() ?: "https://localhost:8080/v1"

    val errorResponse = ApiErrorResponse(
        type = "$baseUrl/errors/$typeStr",
        title = status.description,
        status = status.value,
        detail = detail,
        instance = this.request.uri
    )
    this.respond(status, errorResponse)
}

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
