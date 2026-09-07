package com.yumark.app.data.local.file

import android.content.Context
import android.util.Log
import com.yumark.app.core.export.ExportRetention
import com.yumark.app.core.util.AtomicFileReplace
import com.yumark.app.core.util.PathSafety
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
@Suppress("TooManyFunctions")  // 文件域聚合根：方法即职责清单，拆类只会制造跳转
class FileManager @Inject constructor(
    @ApplicationContext private val context: Context
) : com.yumark.app.domain.repository.ImportFilePort {
    private val documentsDir = File(context.filesDir, "documents")
    private val imagesDir = File(context.filesDir, "images")
    private val exportsDir = File(context.filesDir, "exports")

    // 导入库的图片资产：按导入时的相对目录结构镜像存放，供预览解析相对路径引用
    private val importAssetsDir = File(context.filesDir, "import_assets")

    // AI 视觉附件：用户在 Agent 对话里上传的图片（已下采样），与文档 images 分开
    private val aiAttachmentsDir = File(context.filesDir, "ai_attachments")

    // 远端删除的正文救援副本（DeleteLocal 删除前落盘）。有界保留，见 SyncTrashStore 的类注释。
    private val syncTrashDir = File(context.filesDir, SyncTrashStore.DIR_NAME)
    private val syncTrash = SyncTrashStore(syncTrashDir)

    /**
     * 残留 tmp 清理的一次性闸门。
     *
     * 挂在保存路径上而不是 init：init 在 Hilt 首次注入 FileManager 时跑，那可能是应用启动
     * 的关键路径（冷启动首帧），扫一遍 documents 目录纯属拖慢启动。而 compareAndSet 保证
     * 整个进程只扫一次——自动保存每 30s 一次、防抖保存每停手一次，每次都扫目录是纯浪费。
     */
    private val staleTempCleaned = AtomicBoolean(false)

    /**
     * 每文档一把锁，串行化同一篇文档的写与删。
     *
     * 为什么必须有：同一篇文档存在**两个互不知情的写入方**——编辑器防抖保存（`EditorViewModel`
     * 里的 `stateMutex` 只护住它自己那一个 ViewModel）和 AI Agent 的改写（`AgentUseCases` 自带
     * `saveDocumentUseCase`，完全不经过编辑器）；侧栏改名也会把整篇正文读出再写回。三条路径
     * 落到同一个 tmp 文件上时，`FileOutputStream` 会让两份内容交错，随后先完成的那次把 tmp
     * rename 走，后完成的那次 rename 失败，进而走到「删掉目标文件再重试」——删掉的正是刚
     * rename 上去的好文件。结果是 Room 行还在、`.md` 没了，而 [loadDocumentContent] 把文件缺失
     * 映射成空串，界面上就是「文档还在列表里，打开是空白」。
     *
     * 用 id 而不是全局一把锁：不同文档之间没有共享状态，全局锁会让批量导入退化成串行写盘。
     * 条目不回收，因为一把 Mutex 只有几十字节，且键的上限就是用户文档数。
     */
    private val docLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(id: String): Mutex = docLocks.getOrPut(id) { Mutex() }

    /** 导出目录的保留策略：为什么不是「导出前清空整个目录」见 [ExportRetention] 的类注释。 */
    private val exportRetention = ExportRetention(exportsDir)

    init {
        documentsDir.mkdirs()
        imagesDir.mkdirs()
        exportsDir.mkdirs()
        importAssetsDir.mkdirs()
        aiAttachmentsDir.mkdirs()
        syncTrashDir.mkdirs()
    }

    /**
     * 验证文件 ID 是否安全
     * 只允许 UUID 格式: 字母、数字、连字符
     * 防止路径遍历攻击（如 ../../etc/passwd）
     */
    private fun validateFileId(id: String) {
        // UUID 格式: 8-4-4-4-12 (例如: 550e8400-e29b-41d4-a716-446655440000)
        val uuidPattern = Pattern.compile("^[a-zA-Z0-9-]{1,255}$")

        if (!uuidPattern.matcher(id).matches()) {
            throw SecurityException("Invalid file ID format: $id")
        }

        // 额外检查：不允许包含路径分隔符
        if (id.contains("/") || id.contains("\\") || id.contains("..")) {
            throw SecurityException("File ID contains illegal path characters: $id")
        }
    }

    /**
     * 验证文件路径在指定目录内，防止符号链接与规范化路径绕过。
     *
     * 判定逻辑集中在 [PathSafety.requireInside]（含为什么不能用字符串前缀比较的完整理由）。
     * 这里只保留一层薄封装，是为了让本类的调用点读起来仍是「校验在 documents 目录内」，
     * 同时保证与 `ExportDocumentUseCase` 用的是同一套判定——安全校验有两份实现，
     * 实际强度就等于更弱的那一份。
     */
    private fun validatePathInDirectory(file: File, allowedDir: File) {
        PathSafety.requireInside(file, allowedDir)
    }

    suspend fun saveDocumentContent(id: String, content: String): Result<Unit> =
        // NonCancellable：写盘一旦开始必须完成，避免协程取消留下半截文件
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            // 进程内首次保存时顺手清掉历史残留的 tmp（上次崩溃/断电时 rename 没跑成留下的）。
            // 整段 runCatching 兜住：listFiles 可能因权限返回 null 或抛 SecurityException，
            // 清理失败绝不能连带让这次保存失败——它只是打扫卫生，不是保存的一部分。
            // （runCatching 捕获的是 Throwable，但 deleteStaleTempFiles 内没有挂起点，
            //   不存在把 CancellationException 吞掉的情况。）
            if (staleTempCleaned.compareAndSet(false, true)) {
                runCatching { deleteStaleTempFiles(System.currentTimeMillis() - STALE_TEMP_THRESHOLD_MILLIS) }
            }
            try {
                // 验证 ID 安全性
                validateFileId(id)

                val file = File(documentsDir, "$id.md")

                // 验证文件路径在允许的目录内
                validatePathInDirectory(file, documentsDir)

                // 锁在 NonCancellable 里面：等锁的过程也不允许被取消，否则「前一次保存正在写、
                // 这一次被取消」会让最新内容永远落不了盘。
                lockFor(id).withLock { replaceAtomically(id, file, content) }
                Result.success(Unit)
            } catch (e: SecurityException) {
                Result.failure(e)
            } catch (e: CancellationException) {
                // 必须排在 Exception 之前：CancellationException 是 IllegalStateException 的子类，
                // 被下面那条吞成 Result.failure 之后，调用方会把一次正常的取消当成「保存失败」
                // 弹给用户，同时取消信号也断在这里传不上去。
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun loadDocumentContent(id: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                // 验证 ID 安全性
                validateFileId(id)

                val file = File(documentsDir, "$id.md")

                // 验证文件路径在允许的目录内
                validatePathInDirectory(file, documentsDir)

                // 走 readOrRecover 而不是直接 readText：替换过程有一个窗口（旧文件已挪成
                // .bak、新内容还没 rename 上去）里目标文件是不存在的，进程正好在那时被杀，
                // 直接读就得到「文件不存在」→ 空串 → 用户看到一篇空白正文，而正文其实完好
                // 地躺在 .bak 里。null（连备份都没有）才是真的空文档：新建文档在首次保存前
                // 就没有文件，那时返回 "" 是对的。
                Result.success(AtomicFileReplace.readOrRecover(file) ?: "")
            } catch (e: SecurityException) {
                Result.failure(e)
            } catch (e: CancellationException) {
                // 这条路径是真会被取消的（没有 NonCancellable）：预览/列表切换时上一次读文件
                // 常被取消。吞成 Result.failure 会让上层把它当成「文档读取失败」——
                // observeDocument 里非 FileNotFound 的错误是直接向上抛的，界面就报错了。
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun deleteDocumentFile(id: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                // 验证 ID 安全性
                validateFileId(id)

                val file = File(documentsDir, "$id.md")

                // 验证文件路径在允许的目录内
                validatePathInDirectory(file, documentsDir)

                // 与保存共用同一把锁：不加锁的删除可能插在「.bak 已生成、新内容还没 rename
                // 上去」的中间，删掉一个此刻不存在的目标文件（无效），随后那次保存把文件又
                // 建了回来——文档在列表里已经消失，磁盘上却留着孤儿 .md。
                // 备份一并删掉，否则下一篇复用同一 id 的文档会被 readOrRecover 捞出上一篇的正文。
                lockFor(id).withLock {
                    if (file.exists()) file.delete()
                    AtomicFileReplace.backupOf(file).delete()
                }
                Result.success(Unit)
            } catch (e: SecurityException) {
                Result.failure(e)
            } catch (e: CancellationException) {
                // 同上：CancellationException 不是业务失败。删除本身已经执行过或还没执行，
                // 两种情况都比「把取消当删除失败上报」更容易排查。
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * 删除 images 目录下这批图片文件，返回实际删掉的个数。
     *
     * 调用约定是「名单先取、库行后删、文件最后删」：`images` 行会被 `documents` 的 CASCADE
     * 带走，删完库再查就只剩磁盘上一堆再也没人引用得到的文件（连孤儿清理都找不到它们）。
     * 两个调用方都照这个顺序：[com.yumark.app.data.repository.DocumentRepositoryImpl.deleteDocument]
     * 与 [com.yumark.app.data.repository.FolderRepositoryImpl] 的子树删除。
     *
     * 每个文件单独 runCatching、失败只记日志：库行此刻已经删掉了，为一个删不掉的文件把整次
     * 删除判成失败，用户看到的是「删除失败」而文档其实已经没了——留下的是几 KB 垃圾文件，
     * 不是数据不一致，比反过来轻得多。
     *
     * [fileNames] 是 `images.file_name` 这一列的值（本应用自己生成），但它毕竟来自数据库：
     * 拼出来的路径仍然过一遍唯一那份包含校验（[PathSafety.requireInside]，按路径段比较），
     * 以防日后有别的写入方把带 `../` 的相对路径塞进这一列。
     *
     * 这个函数是「带 PathSafety 校验的图片删除」的唯一实现：两个仓库各抄一份的话，
     * 实际强度就等于其中更弱的那一份。
     */
    suspend fun deleteImageFiles(fileNames: Collection<String>): Int {
        if (fileNames.isEmpty()) return 0
        return withContext(Dispatchers.IO) {
            var deleted = 0
            for (fileName in fileNames) {
                val ok = runCatching {
                    val file = File(imagesDir, fileName)
                    PathSafety.requireInside(file, imagesDir, label = "Image path")
                    file.delete()
                }.onFailure { e ->
                    Log.w(TAG, "删除图片文件失败：${fileName}", e)
                }.getOrDefault(false)
                if (ok) deleted++
            }
            deleted
        }
    }

    /**
     * 把 [content] 落到 [file]，中转 tmp 一次一名。
     *
     * 中转文件名里那段 [UUID] 是这次修复的核心：从前用的是固定的 `<id>.md.tmp`，同一篇文档
     * 的两次写入会**共用同一个 FileOutputStream 目标**，两份正文交错写进去，先完成的一方把
     * tmp rename 走，后完成的一方 rename 失败后去删目标文件——删掉的正是刚刚写好的那份。
     * 名字唯一之后，两次写入各写各的 tmp，最坏情况只是后写的覆盖先写的（这本就是「后一次
     * 保存生效」的正确语义）。
     *
     * 命名规则必须与 [isOwnTempName] 对得上，否则残留清理认不出自己写的文件。
     */
    private fun replaceAtomically(id: String, file: File, content: String) {
        val tmp = File(documentsDir, "$id.${UUID.randomUUID()}$TEMP_SUFFIX")
        AtomicFileReplace.replace(file, content, tmp)
    }

    /**
     * 手动触发残留 tmp 清理，返回删掉的个数。
     *
     * 保存路径上的自动清理每进程只跑一次（见 [staleTempCleaned]），长时间不重启的会话
     * 可以用这个再清一遍；也留给启动期或设置页的「清理缓存」调用。
     */
    suspend fun cleanStaleTempFiles(staleMillis: Long = STALE_TEMP_THRESHOLD_MILLIS): Int =
        withContext(Dispatchers.IO) {
            deleteStaleTempFiles(System.currentTimeMillis() - staleMillis)
        }

    /**
     * 删除 documents 目录下过期的残留 tmp，返回实际删掉的个数。
     *
     * 三重保险都是为了绝不删到用户数据：
     * - 只处理 isFile（顺带跳过目录，以及 listFiles 返回 null 的无权限情况）；
     * - 文件名必须完整符合本类自己的命名规则（[isOwnTempName]），别人放进这个目录的
     *   同后缀文件不碰；
     * - lastModified() 不早于 deadline 的一律留着——那可能是**另一次保存正在写**的 tmp
     *   （saveDocumentContent 写的就是这个目录），删掉等于打断进行中的保存。
     *
     * 每个 delete 单独 runCatching：一个删不掉（被占用、权限）不能影响后面的。
     */
    private fun deleteStaleTempFiles(deadline: Long): Int {
        val candidates = documentsDir.listFiles() ?: return 0
        var deleted = 0
        for (file in candidates) {
            if (!file.isFile) continue
            if (!isOwnTempName(file.name)) continue
            if (file.lastModified() >= deadline) continue
            if (runCatching { file.delete() }.getOrDefault(false)) deleted++
        }
        return deleted
    }

    /**
     * 名字是否是本类写出的 tmp。两种形态都要认：
     * - `<id>.md.tmp`：旧版固定名。上一版留在用户目录里的残留仍要清得掉，认不出就永远清不掉；
     * - `<id>.<token>.md.tmp`：现在的名字，token 是一次性 UUID（见 [replaceAtomically]）。
     *
     * 两段都复用 [validateFileId] 判定而不另写正则：规则一旦漂移，轻则漏删，重则把别人放进
     * 这个目录的文件当成自己的删掉。文档 id 本身不含点（[validateFileId] 只放行
     * 字母/数字/连字符），所以在第一个点上切分能可靠地还原 id 与 token。
     */
    private fun isOwnTempName(name: String): Boolean {
        if (!name.endsWith(TEMP_SUFFIX)) return false
        val stem = name.removeSuffix(TEMP_SUFFIX)
        if (stem.isEmpty()) return false
        val id = stem.substringBefore('.')
        val token = stem.substringAfter('.', missingDelimiterValue = "")
        if (runCatching { validateFileId(id) }.isFailure) return false
        return token.isEmpty() || runCatching { validateFileId(token) }.isSuccess
    }

    fun getDocumentsDir(): File = documentsDir
    fun getImagesDir(): File = imagesDir
    fun getExportsDir(): File = exportsDir
    fun getImportAssetsDir(): File = importAssetsDir

    /**
     * 把导入图片复制到 import_assets 镜像目录，保留相对结构；单张失败/超限不中断整体。
     * [images] 的元素只需提供 uri / displayName / relativeFolderPath 三样。
     * 超过单张大小上限的：不留半截文件（边复制边限量，删半截）。
     */
    override fun copyImportImages(
        context: android.content.Context,
        images: List<com.yumark.app.domain.repository.ImportFilePort.ImportImageSpec>
    ) {
        for (image in images) {
            runCatching {
                val dir = image.relativeFolderPath
                    .map { sanitizeImportSegment(it) }
                    .fold(importAssetsDir) { parent, segment -> File(parent, segment) }
                dir.mkdirs()
                val target = File(dir, sanitizeImportSegment(image.displayName))
                val complete = context.contentResolver.openInputStream(android.net.Uri.parse(image.uri))
                    ?.use { input -> copyWithLimit(input, target, MAX_IMPORT_IMAGE_BYTES) } ?: false
                if (!complete) target.delete()
            }
        }
    }

    /** 边复制边限量，超过 [maxBytes] 返回 false（调用方删半截文件）。 */
    private fun copyWithLimit(input: java.io.InputStream, target: File, maxBytes: Long): Boolean {
        target.outputStream().use { out ->
            val buf = ByteArray(COPY_BUFFER_BYTES)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) return true
                total += n
                if (total > maxBytes) return false
                out.write(buf, 0, n)
            }
        }
    }

    fun getAiAttachmentsDir(): File = aiAttachmentsDir

    /**
     * 远端删除落地前的正文救援：把最后一份已落盘正文复制进 `sync_trash/`。
     *
     * 只在 `SyncRepositoryImpl` 的 DeleteLocal 分支调用，用户手动删除**不走这里**（用户删是
     * 明确意图，另留版本史与 WebDAV 兜底；这里救的是「另一台设备上的删除传播到本机」——
     * 本机用户从未表达过这个意图）。
     *
     * 任何失败都抛（[AtomicFileReplace.replace] 的 IOException 契约）：调用方以 getOrThrow
     * 把整个删除动作判成失败——救援写不进去却照删，正是 [SyncTrashStore] 类注释里那个
     * 「任何地方都不再存在」的原始事故。远端文件已没了，下轮重判仍是 DeleteLocal，
     * 重试幂等，失败不会把同步打死。
     *
     * 走磁盘内容而不是调用方内存里的 content 参数：内存值可能领先于磁盘（防抖窗口内），
     * 而 DeleteLocal 的前置条件（本地与基线一致）约束的是**已落盘**内容——磁盘上那份
     * 才是「另一台设备最后见过的版本」，也正是该救的那一份。
     */
    suspend fun rescueBeforeRemoteDelete(id: String, title: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                validateFileId(id)
                // readOrRecover：防抖/自动保存与同步并发时的窗口里目标可能暂时是 .bak
                val content = AtomicFileReplace.readOrRecover(File(documentsDir, "$id.md"))
                    ?: ""
                syncTrash.rescue(id, title, content)
                Result.success(Unit)
            } catch (e: SecurityException) {
                Result.failure(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** 设置页「同步删除的正文备份」入口用。只读，不提供任何删除方法——清空走 [clearSyncTrash]。 */
    fun getSyncTrashDir(): File = syncTrashDir

    /** 救援副本的导出与计数（设置页入口）。 */
    fun syncTrashCount(): Int = syncTrash.count()

    fun syncTrashExportText(): String = syncTrash.exportText()

    /** 清空救援副本，返回删掉的份数。 */
    fun syncTrashClear(): Int = syncTrash.clear()

    /**
     * 应用私有根目录（上面五个目录的公共父目录）。
     *
     * 只有一个用途：正文里 `images/<uuid>.jpg` 这种引用要在 WebView 里显示，需要一个
     * `file://<根>/` 前缀，让 `renderer.js` 把相对引用拼成绝对 URL。给根而不是直接给
     * `images/`，是因为拼接规则（含 `images/` 这一段）写在 JS 里，两侧各留一半会拼错。
     *
     * 不要拿它去读写具体文件——落盘一律走上面五个目录的 getter，它们各自带路径校验。
     */
    fun getFilesRootDir(): File = context.filesDir

    /**
     * 清理导出目录里的旧文件，返回删掉的份数。在**写新导出之前**调用。
     *
     * 放在导出前而不是导出后：导出后清理必须小心别把刚写出、马上要分享的那份删掉，
     * 多一条特例就多一处能写错的地方；导出前清理时最新一份是上一次的导出，正好由
     * [ExportRetention] 的宽限期保护。
     */
    suspend fun pruneExports(): Int = withContext(Dispatchers.IO) { exportRetention.prune() }

    companion object {
        private const val TAG = "FileManager"

        /** 原子写用的临时文件后缀。写入与清理必须共用一份，否则清理认不出自己写的文件。 */
        const val TEMP_SUFFIX = ".md.tmp"

        /**
         * tmp 超过这个年龄才算「残留」。
         *
         * 5 分钟是给「正在写」留的安全边界：单篇文档写入实际是毫秒级，即便设备卡顿叠加
         * 大文档也不该到分钟级。取小了会误删并发写入中的 tmp（等于弄坏一次保存），
         * 取大了只是让垃圾多留一会儿——两边代价完全不对称，所以宁可偏大。
         */
        const val STALE_TEMP_THRESHOLD_MILLIS = 5 * 60 * 1000L

        /**
         * 导入库镜像目录（import_assets）的路径段消毒：防 SAF 返回的名称带路径分隔符或 ".."。
         * 写镜像（导入复制）与算镜像路径（重命名/删除同步）必须用同一规则，否则对不上。
         */
        /** 导入单张图片的大小上限（与旧 ImportFolderUseCase 的 MAX_IMAGE_BYTES 一致）。 */
        private const val MAX_IMPORT_IMAGE_BYTES = 25L * 1024 * 1024

        /** 流式复制的缓冲区大小。 */
        private const val COPY_BUFFER_BYTES = 64 * 1024

        fun sanitizeImportSegment(segment: String): String {
            val cleaned = segment.replace('/', '_').replace('\\', '_')
            return if (cleaned == "..") "_" else cleaned
        }
    }
}
