package net.home.server

import java.io.File

enum class DirectoryConfig(val path: String) {
    // Dockerコンテナ内のプロジェクトルート配下のdataディレクトリ
    DATA_ROOT("/workspaces/home-server-api");

    val file: File get() = File(path)
}
