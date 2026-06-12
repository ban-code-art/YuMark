package com.yumark.app.presentation.navigation

import java.net.URLEncoder

sealed class Screen(val route: String) {
    data object FileList : Screen("files")
    data object Editor : Screen("editor?documentId={documentId}&docUri={docUri}") {
        fun createRoute(documentId: String) = "editor?documentId=$documentId"
        fun createExternalRoute(docUri: String) =
            "editor?docUri=${URLEncoder.encode(docUri, "UTF-8")}"
    }
    data object Settings : Screen("settings")
}
