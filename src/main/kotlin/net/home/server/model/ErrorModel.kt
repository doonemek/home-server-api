package net.home.server.model

import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorResponse(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String
)
