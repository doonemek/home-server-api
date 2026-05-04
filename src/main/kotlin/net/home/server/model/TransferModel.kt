package net.home.server.model

import kotlinx.serialization.Serializable

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
