package net.home.server.util

import java.time.format.DateTimeFormatter
import java.time.ZoneId

// 共通のフォーマッタ (ISO 8601 / UTC)
val dateFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    .withZone(ZoneId.of("UTC"))
