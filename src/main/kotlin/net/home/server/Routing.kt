package net.home.server

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

fun Application.configureRouting() {
    routing {
        route("/v1") {
            get("/read-sample") {
                // コンテナ内のパスを指定します
                val file = File("/workspaces/home-server-api/data/sample")

                if (file.exists()) {
                    val content = file.readText()
                    call.respondText("ファイルの中身: $content")
                } else {
                    call.respondText("ファイルが見つかりません。")
                }
            }

            fileOperationRoutes()

            dataTransferRoutes()


        }
    }
}
