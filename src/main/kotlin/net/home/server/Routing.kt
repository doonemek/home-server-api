package net.home.server

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import kotlinx.serialization.Serializable


// JSON用のデータ型
@Serializable
data class FileInfo(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val children: List<FileInfo>? = null
)

// 再帰的にファイル構造を構築する関数
fun getFileTree(file: File): FileInfo {
    val isDirectory = file.isDirectory
    val children = if (isDirectory) {
        // ディレクトリなら、中身を一つずつ getFileTree にかけてリスト化する（再帰）
        file.listFiles()?.map { getFileTree(it) }
    } else {
        null
    }

    return FileInfo(
        name = file.name,
        isDirectory = isDirectory,
        size = if (isDirectory) 0 else file.length(),
        children = children
    )
}

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
            // フォルダ内のファイル一覧を JSON で返す
            // 子dir があればその中身まで返す。
            get("/contents") {
                val rootDir = File("data")
                val tree = rootDir.listFiles()?.map { getFileTree(it) } ?: emptyList()

                call.respond(tree)
            }

            dataTransferRoutes()


        }
    }
}
