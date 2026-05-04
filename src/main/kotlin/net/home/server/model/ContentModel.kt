package net.home.server.model

import kotlinx.serialization.Serializable

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
