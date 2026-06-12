package com.yumark.app.core.util

import android.net.Uri
import android.provider.DocumentsContract

/**
 * SAF 文件选择器的位置辅助。
 *
 * 系统的 OpenDocumentTree 选择器默认记住「上次浏览的目录」并直接打开在那里，
 * 嵌套使用时容易让用户误以为只能选上次的文件夹。通过 EXTRA_INITIAL_URI
 * （即 contract 的 launch 入参）提示选择器每次都从内部存储根目录开始浏览。
 * 部分 ROM 可能忽略该提示，因此调用方仍需配合「选择结果回显确认」兜底。
 */
object SafLocations {

    /** 手机内部存储根目录的位置提示；构造失败时返回 null（选择器回退默认行为） */
    fun storageRootHint(): Uri? = runCatching {
        DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:"
        )
    }.getOrNull()
}
