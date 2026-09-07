package com.yumark.app.domain.repository

/**
 * 导入侧文件操作的窄契约（依赖倒置）：use case 只需要「复制图片资产到镜像目录」，
 * 不需要 FileManager 的另外 30 个方法——窄接口把可替换面收到最小。
 */
interface ImportFilePort {

    /** 导入图片的复制描述（镜像目录 + 相对结构）。 */
    data class ImportImageSpec(
        val uri: String,
        val displayName: String,
        val relativeFolderPath: List<String>
    )

    /**
     * 复制图片到 import_assets 镜像目录，保留相对结构；单张失败/超限不中断整体。
     * 超过单张大小上限的不留半截文件。
     */
    fun copyImportImages(context: android.content.Context, images: List<ImportImageSpec>)
}
