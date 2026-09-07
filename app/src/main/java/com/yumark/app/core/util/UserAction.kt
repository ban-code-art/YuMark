package com.yumark.app.core.util

import androidx.annotation.StringRes
import com.yumark.app.R

/**
 * 「当时正在做的事」。[ErrorHandler] 用它拼出「保存失败：无法读写文件…」的前半句。
 *
 * 为什么是枚举而不是直接传 `@StringRes Int`：一条失败要同时喂两个受众——
 * - [labelRes] 给用户，随语区翻译；
 * - [tag] 给崩溃日志的分诊标签，必须在**拿不到 Context** 的 [ErrorHandler.report] 里就能写进
 *   非致命记录。若只传资源 id，那里除了一串 `0x7f0e…` 什么都写不出来，日志里就只剩
 *   「存储读写」这种粗粒度场景，看不出是保存还是导出出的事。
 *
 * [tag] 刻意保持中文：它与 [AppError.scene] 拼成同一条 note（形如「保存 / 存储读写」），
 * 本项目的崩溃日志本来就是中文产物，换成英文只会让同一行里两种语言混排。
 */
enum class UserAction(@StringRes val labelRes: Int, val tag: String) {
    // ---- 文档 / 文件夹 ----
    READ_FILE(R.string.action_read_file, "读取文件"),
    OPEN_DOCUMENT(R.string.action_open_document, "打开文档"),
    CREATE_DOCUMENT(R.string.action_create_document, "创建文档"),
    RENAME_DOCUMENT(R.string.action_rename_document, "重命名文档"),
    DELETE_DOCUMENT(R.string.action_delete_document, "删除文档"),
    RESTORE_DOCUMENT(R.string.action_restore_document, "恢复文档"),
    TRASH_LOAD(R.string.action_trash_load, "加载回收站"),
    MOVE_DOCUMENT(R.string.action_move_document, "移动文档"),
    CREATE_FOLDER(R.string.action_create_folder, "创建文件夹"),
    CREATE_SUBFOLDER(R.string.action_create_subfolder, "创建子文件夹"),
    RENAME_FOLDER(R.string.action_rename_folder, "重命名文件夹"),
    DELETE_FOLDER(R.string.action_delete_folder, "删除文件夹"),
    MOVE_FOLDER(R.string.action_move_folder, "移动文件夹"),
    OPEN_FOLDER(R.string.action_open_folder, "打开文件夹"),
    REFRESH(R.string.action_refresh, "刷新"),
    SEARCH(R.string.action_search, "搜索"),

    // ---- 编辑器 ----
    SAVE(R.string.action_save, "保存"),
    SAVE_ON_EXIT(R.string.action_save_on_exit, "退出时保存"),
    EXPORT(R.string.action_export, "导出"),
    VERSION_SNAPSHOT(R.string.action_version_snapshot, "历史版本快照"),
    OPEN_LINK(R.string.action_open_link, "打开链接"),

    // ---- 导入 ----
    CREATE_IMPORT_LIBRARY(R.string.action_create_import_library, "创建导入库"),
    IMPORT(R.string.action_import, "导入"),
    IMPORT_FOLDER(R.string.action_import_folder, "导入文件夹"),
    SCAN_FOLDER(R.string.action_scan_folder, "扫描文件夹"),

    // ---- AI ----
    ADD_IMAGE(R.string.action_add_image, "添加图片"),
    LOAD_TARGET_DOCUMENT(R.string.action_load_target_document, "加载目标文档内容"),
    FETCH_MODELS(R.string.action_fetch_models, "获取模型列表"),
    RAG_ENQUEUE(R.string.action_rag_enqueue, "RAG 索引入队"),
    /** Agent 应用改写/新建等动作的兜底标签——那一步具体做什么由模型决定，说不出更细的名字。 */
    APPLY_ACTION(R.string.action_apply_action, "操作"),
    /** Agent 长会话的历史压缩（后台摘要）。失败只留非致命记录，对话回退纯裁剪。 */
    COMPRESS_HISTORY(R.string.action_compress_history, "压缩对话历史"),

    // ---- 设置 / 同步 ----
    CHECK_UPDATE(R.string.action_check_update, "检查更新"),
    /** 设置项写盘（字号、自动保存、压缩、主题、恢复默认…）——失败都是 DataStore 写不进去。 */
    SAVE_SETTINGS(R.string.action_save_settings, "保存设置"),
    EXPORT_CONFIG(R.string.action_export_config, "导出配置"),
    IMPORT_CONFIG(R.string.action_import_config, "导入配置"),
    EXPORT_CRASH_LOGS(R.string.action_export_crash_logs, "导出崩溃日志"),
    CONNECT(R.string.action_connect, "连接"),
    SYNC(R.string.action_sync, "同步"),

    // ---- WebDAV 单步操作 ----
    // 同步是由这四步拼起来的，失败要说清卡在哪一步：「上传失败：HTTP 507」比
    // 「同步失败：HTTP 507」能直接指向「远端空间不足」，而后者还得让用户猜。
    LIST_REMOTE_DIR(R.string.action_list_remote_dir, "列目录"),
    DOWNLOAD_REMOTE_FILE(R.string.action_download_remote_file, "下载"),
    UPLOAD_REMOTE_FILE(R.string.action_upload_remote_file, "上传"),
    CREATE_REMOTE_DIR(R.string.action_create_remote_dir, "创建目录")
}
