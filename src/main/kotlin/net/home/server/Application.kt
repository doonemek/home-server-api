package net.home.server

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    // アプリで JSON 使用可能な状態にする
    install(ContentNegotiation) {
        json()
    }

    // Routing.ktを呼び出す
    configureRouting()
}
