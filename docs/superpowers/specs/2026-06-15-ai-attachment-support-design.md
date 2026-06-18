# AI 助手附件支持功能设计文档

**日期**: 2026-06-15
**版本**: v1.1（2026-06-16 修订，与现有代码对齐 + 补齐缺口）
**阶段**: Phase 1 - 划词快捷对话框图片支持

> 修订记录见文末第 17 节。本次修订未改变 Phase 1 范围（仍为：划词快捷对话框 + 图片 + OpenAI/Claude），主要是修正与真实代码不符之处、复用现有基础设施、补齐被遗漏的设计点。

---

## 1. 概述

### 1.1 目标

为 YuMark 的 AI 助手「划词快捷对话框」（`AiQuickDialog`，含询问 / 处理两种模式）添加图片附件支持，使用户能够：
- 选择图片让 AI 进行视觉分析
- 基于图片内容进行对话
- 处理（Agent）模式下结合图片内容修改选中文本

### 1.2 范围

**Phase 1 包含**：
- 图片选择（系统相册 Photo Picker、相机拍照）
- 图片校验、按需下采样 / 压缩、Base64 编码
- 多模态 API 调用（OpenAI、Claude）
- UI：附件预览、缩略图、点击查看大图
- 单条消息最多 3 张图片

**Phase 1 不包含**（见第 15 节 Phase 2）：
- 主 AI 助手（`AiAssistantHost` 的 Chat / Agent）的附件支持与 **Room 持久化**
- 文件管理器选择、非图片文件
- Gemini 视觉支持
- 图片编辑（裁剪、旋转）

### 1.3 约束与术语（重要：区分两类「大小」）

附件大小有两层含义，必须分开看：

| 概念 | 含义 | Phase 1 取值 |
|------|------|------|
| **源文件大小** | 用户选中的原始图片字节数 | 校验上限 10MB，超出直接拒绝 |
| **发送前长边** | 编码进 API 前下采样的最长边像素 | 默认 ≤ 1568px（兼顾成本与各家上限） |
| **编码后载荷** | Base64 后进入请求体的大小 | Base64 比原始字节膨胀约 **33%** |

> 之前 v1.0 用单一「5MB」既当源文件上限又当 API 上限，是不准确的：Claude 单图上限是**编码后** ≤5MB，OpenAI/Claude 服务端都会对超大图再压缩、且按像素计 input token——所以发送前下采样长边既能避免触限、又能显著省 token。

其它约束：
- 支持格式：JPG、PNG、GIF、WEBP（与现有 `ImageRepository` 一致）
- 附件预览在输入框上方，横向滚动；缩略图点击查看大图
- 划词快捷对话框的附件为**内存态**（见 1.4）

### 1.4 Phase 1 明确取舍

划词快捷对话框的对话历史（`ConversationMessage`）**不落 Room**，仅存在于 `AiQuickViewModel` 的内存 `StateFlow` 中；`onOpen()` 的「相同选区恢复」也只在进程存活期间有效。因此：
- 附件随对话历史保存在内存，**进程被杀即丢失**——这对「划词→快速处理→关闭」的轻量场景可接受。
- 主 AI 助手的持久化附件（需要 Room 迁移 + 图片文件存储）属于 Phase 2，本文不展开。

---

## 2. 与现有代码对齐（本次修订新增）

实现前必须复用以下既有设施，避免重复造轮子：

| 既有设施 | 位置 | 复用方式 |
|---------|------|---------|
| **图片解码 / 缩放 / 压缩 / 落盘** | `ImageRepositoryImpl.saveImage()`（`data/repository/`） | 已实现「解码 → 按 `maxImageWidth` 缩放 → 按 `imageCompressionQuality` 压缩 → 存 `FileManager.getImagesDir()`」。抽取其中**解码+缩放+压缩为字节流**的纯逻辑供 `ImageProcessor` 复用 |
| **图片压缩相关设置** | `UserSettings.autoCompressImages` / `maxImageWidth` / `imageCompressionQuality`（`SettingsRepository.getSettings()`） | 直接复用，**不要**硬编码 `COMPRESSION_QUALITY=85` |
| **FileProvider** | `AndroidManifest.xml` 已注册 authority `${applicationId}.fileprovider` + `@xml/file_paths` | 相机临时文件直接用此 authority，**不新增 provider** |
| **图片异步加载** | Coil（`libs.coil.compose` 已在依赖中） | 缩略图 / 大图用 `AsyncImage` |
| **ActivityResult** | `libs.androidx.activity.compose` 已在依赖中 | `rememberLauncherForActivityResult`，无需新增依赖 |
| **错误反馈范式** | `EditorViewModel` 的 `saveError` / `applyError` → `Snackbar` | 附件错误沿用 `StateFlow<String?>` + Snackbar |
| **结果类型** | 全代码库统一 `kotlin.Result`（`runCatching` / `Result.success/failure`） | `ImageProcessor` 与 ViewModel 沿用，**禁止**自造 `Result.Success/Failure` |

`ImageProcessor` 与 `ImageRepository` 的分工：`ImageRepository` 面向「文档内 Markdown 图片」，会写 images 表、绑定 `documentId`；**AI 附件是临时的，不应污染文档图片表**。因此 `ImageProcessor` 只负责「校验 + 为视觉用途下采样 + Base64 + 缩略图」，不落库。两者共享底层 Bitmap 处理逻辑即可。

---

## 3. 数据模型设计

### 3.1 多模态内容（线格式，发送时构造）

```kotlin
// app/src/main/java/com/yumark/app/domain/model/MessageContent.kt

sealed class MessageContent {
    /** 文本内容 */
    data class Text(val text: String) : MessageContent()

    /**
     * 图片内容——Provider 中立。
     * 只存「裸 Base64 + mimeType」，不预先拼成 data URL；
     * 由各适配器按自家协议格式化（见第 5 节）。
     */
    data class Image(
        val base64: String,        // 纯 Base64，无 "data:..." 前缀
        val mimeType: String,      // image/jpeg | image/png | image/gif | image/webp
        val width: Int? = null,
        val height: Int? = null
    ) : MessageContent()
}
```

> **修正**：v1.0 的 `MessageContent.Image.uri` 里塞了 `data:image/...;base64,...`（OpenAI 形态）。但 Claude 要的是 `source{type:"base64", media_type, data}` 的**裸** Base64——若按 v1.0 存，Claude 适配器会拿到带 `data:` 前缀的脏数据。故改为存裸 Base64，前缀由 OpenAI 适配器在发送时拼接。

### 3.2 扩展 ChatMessage（向后兼容）

```kotlin
// app/src/main/java/com/yumark/app/domain/model/AiApiModels.kt

data class ChatMessage(
    val role: String,                                     // "user" | "assistant" | "system"
    val content: String,                                  // 保留：纯文本路径仍只用它
    val contentParts: List<MessageContent> = emptyList()  // 新增：非空即走多模态
)
```

**兼容策略**：`contentParts` 为空时，适配器走原有「`content` 为字符串」分支（现网行为不变）；非空时走多模态分支。

### 3.3 UI / 历史中的附件引用（轻量，用于显示）

显示缩略图只需本地 `Uri`，**不应**把 Base64 留在历史里（内存爆炸且无必要）。复用 v1.0 已定义的 `ImageAttachment`：

```kotlin
// 与 AiQuickViewModel 同文件

data class ImageAttachment(
    val uri: Uri,            // 本地 content:// / file://，供 Coil 显示
    val info: ImageInfo,     // mime/尺寸/字节数
    val thumbnail: Bitmap? = null
)
```

```kotlin
// app/src/main/java/com/yumark/app/presentation/editor/AiQuickDialog.kt

data class ConversationMessage(
    val role: MessageRole,
    val content: String,
    val mode: QuickAiMode,
    val attachments: List<ImageAttachment> = emptyList()  // 用 Uri 显示，非 Base64
)
```

**数据流**：`ImageAttachment`（选择/显示，持 Uri） → 发送时由 `ImageProcessor` 现编码为 `MessageContent.Image`（裸 Base64） → 放进 `ChatMessage.contentParts`；Base64 **不回写**历史。

---

## 4. 图片处理模块

### 4.1 ImageProcessor

```kotlin
// app/src/main/java/com/yumark/app/core/image/ImageProcessor.kt

class ImageProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository  // 复用压缩设置
) {
    companion object {
        const val MAX_SOURCE_BYTES = 10_000_000L  // 源文件硬上限 10MB
        const val VISION_MAX_EDGE = 1568          // 发送前长边下采样目标（省 token / 防触限）
        const val THUMBNAIL_SIZE = 100

        val SUPPORTED_MIME_TYPES = setOf(
            "image/jpeg", "image/png", "image/gif", "image/webp"
        )
    }

    /** 校验格式与源文件大小 */
    suspend fun validate(uri: Uri): Result<ImageInfo>

    /** 为视觉用途处理：必要时下采样长边到 VISION_MAX_EDGE，再按设置压缩，返回字节流 */
    suspend fun processForVision(uri: Uri): Result<ProcessedImage>

    /** 字节流 → 裸 Base64（无 data: 前缀） */
    fun encodeToBase64(bytes: ByteArray): String

    /** 生成缩略图（Coil/BitmapFactory，用于附件预览） */
    suspend fun generateThumbnail(uri: Uri): Result<Bitmap>
}

data class ImageInfo(
    val uri: Uri,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int
)

data class ProcessedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int
)
```

> 全部返回 `kotlin.Result`；调用方用 `fold` / `getOrElse` / `onFailure` 处理（见第 8 节）。GIF 不做下采样（避免丢帧），仅校验大小后直接编码。

### 4.2 处理策略

```
校验阶段（validate）:
    IF mimeType ∉ SUPPORTED            -> Result.failure("不支持的格式")
    IF sizeBytes > MAX_SOURCE_BYTES    -> Result.failure("图片过大（>10MB）")
    ELSE                                -> Result.success(ImageInfo)

发送前处理（processForVision，复用 ImageRepository 的 Bitmap 逻辑）:
    解码 Bitmap
    IF 长边 > VISION_MAX_EDGE          -> 等比缩放到长边 = VISION_MAX_EDGE
    压缩 (format 按原 mime；质量取 settings.imageCompressionQuality，回退 85)
    返回字节流
    // 注：Base64 后膨胀 ~33%。下采样到 1568px 长边后，
    //     绝大多数照片编码后远低于 Claude 单图 5MB 上限。
```

---

## 5. API 适配器扩展

### 5.1 改造点

`AiApiAdapter` 接口**不变**（仍 `sendChatStream(messages, config)`）。改造各适配器 `sendChatStream` 内构建请求体的部分：当某条 `ChatMessage.contentParts` 非空时，把 `content` 由字符串改为**数组**。三个适配器现都用 `kotlinx.serialization` 的 `buildJsonObject` / `putJsonArray` / `addJsonObject` 构建（见 `ClaudeAdapter.kt` / `OpenAiAdapter.kt`），新增代码须沿用同一套 DSL。

### 5.2 OpenAI（`OpenAiAdapter.kt`）

现有（纯文本）：
```kotlin
messages.forEach { m ->
    addJsonObject { put("role", m.role); put("content", m.content) }
}
```
改造为：
```kotlin
messages.forEach { m ->
    addJsonObject {
        put("role", m.role)
        if (m.contentParts.isEmpty()) {
            put("content", m.content)                 // 旧路径不变
        } else {
            putJsonArray("content") {
                m.contentParts.forEach { part ->
                    when (part) {
                        is MessageContent.Text -> addJsonObject {
                            put("type", "text"); put("text", part.text)
                        }
                        is MessageContent.Image -> addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                // OpenAI 需要 data URL
                                put("url", "data:${part.mimeType};base64,${part.base64}")
                            }
                        }
                    }
                }
            }
        }
    }
}
```

### 5.3 Claude（`ClaudeAdapter.kt`）

Claude 已有 `x-api-key` / `anthropic-version: 2023-06-01` 头、顶层 `system`、过滤 `role == "system"`，这些保持不变。content 改造：
```kotlin
messages.filter { it.role != "system" }.forEach { m ->
    addJsonObject {
        put("role", m.role)
        if (m.contentParts.isEmpty()) {
            put("content", m.content)
        } else {
            putJsonArray("content") {
                m.contentParts.forEach { part ->
                    when (part) {
                        is MessageContent.Text -> addJsonObject {
                            put("type", "text"); put("text", part.text)
                        }
                        is MessageContent.Image -> addJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", part.mimeType)  // 单独字段
                                put("data", part.base64)          // 裸 Base64，无前缀
                            }
                        }
                    }
                }
            }
        }
    }
}
```

> 同一份 `MessageContent.Image`（裸 Base64 + mimeType）被两家适配器各自格式化——这正是 3.1 把图片设计成 Provider 中立的原因。

---

## 6. 视觉能力识别（本次修订新增）

`AiConfig` 没有「是否支持视觉」的元数据，且 `OPENAI_COMPATIBLE` 下模型名完全由用户填写（Ollama / DeepSeek / vLLM 等），无法可靠枚举。策略：**不硬阻断，发出去让 API 说话**。

1. **主路径**：照常发请求；若模型不支持图片，API 会返回 4xx，经 `StreamEvent.Error` 优雅展示（见第 8 节错误表）。
2. **辅助提示（非阻断）**：当用户已添加附件、但当前 `modelName` 不含已知视觉关键字时，输入区上方给一条浅色提示「当前模型可能不支持图片，可继续尝试或更换模型」。关键字白名单（大小写不敏感、子串匹配）：
   ```
   gpt-4o, gpt-4.1, gpt-4-vision, o1, o3, o4,
   claude-3, claude-4, claude-opus, claude-sonnet, claude-haiku,
   vision, -vl, llava, qwen-vl, gemini
   ```
3. 不做硬校验（白名单只用于提示），避免误伤新模型。

---

## 7. UI 设计

### 7.1 AiQuickDialog 布局

```
┌─────────────────────────────────────┐
│ ✨ AI 助手    [💬 询问] [🤖 处理]    │
├─────────────────────────────────────┤
│ 选中的文本： [文本内容...]          │
├─────────────────────────────────────┤
│ [对话历史]                          │
│  用户: 文本消息                     │
│  [缩略图1] [缩略图2]  ← 图片附件    │
│  AI: 回复...                        │
├─────────────────────────────────────┤
│ (可选)当前模型可能不支持图片…  ← 第6节提示 │
│ [附件预览区]  [缩略图][X] [缩略图][X]│
├─────────────────────────────────────┤
│ [📎] [输入框]                  [发送]│
└─────────────────────────────────────┘
```

### 7.2 选择器：相册用 Photo Picker（免权限），相机用 TakePicture

```kotlin
// 相册：Android Photo Picker，无需任何存储权限（API 19+ 经 androidx 回退）
val pickMedia = rememberLauncherForActivityResult(
    ActivityResultContracts.PickVisualMedia()
) { uri -> if (uri != null) viewModel.addAttachment(uri) }

// 相机：拍照到 FileProvider 临时文件
var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
val takePicture = rememberLauncherForActivityResult(
    ActivityResultContracts.TakePicture()
) { success -> if (success) pendingCameraUri?.let { viewModel.addAttachment(it) } }

val requestCamera = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
) { granted ->
    if (granted) {
        val uri = createCameraTempUri(context)   // FileProvider: ${applicationId}.fileprovider
        pendingCameraUri = uri
        takePicture.launch(uri)
    } else { /* Snackbar 引导去设置 */ }
}

// 触发
pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
```

> `createCameraTempUri` 用现有 FileProvider authority（`${applicationId}.fileprovider`）+ `@xml/file_paths`（必要时在该 xml 增加 `<cache-path>` 条目）。**不**新增 provider。

### 7.3 附件菜单 / 预览 / 删除

附件按钮（📎）弹 `DropdownMenu`：「从相册选择」「拍照」。
附件预览区 `AttachmentPreviewRow`（横向滚动）+ `AttachmentPreviewItem`（`AsyncImage` + 右上角删除按钮）。
组件结构沿用 v1.0 第 5.3 / 5.4 节示例，模型从 `attachment.uri` 取图。

### 7.4 消息中的附件显示

`ConversationMessage.attachments` 非空时，在气泡上方用 `AsyncImage` 排一行缩略图（`take(3)`），点击弹 `ImageViewDialog` 看大图。沿用 v1.0 第 5.5 节示例，但数据源为 `ImageAttachment.uri`（非 data URL）。

---

## 8. ViewModel 扩展

```kotlin
class AiQuickViewModel @Inject constructor(
    private val configRepository: AiConfigRepository,
    private val adapterFactory: AiAdapterFactory,
    private val imageProcessor: ImageProcessor          // 新增注入
) : ViewModel() {

    private val _attachments = MutableStateFlow<List<ImageAttachment>>(emptyList())
    val attachments: StateFlow<List<ImageAttachment>> = _attachments.asStateFlow()

    // 复用现有 Snackbar 范式（与 saveError/applyError 一致）
    private val _attachmentError = MutableStateFlow<String?>(null)
    val attachmentError: StateFlow<String?> = _attachmentError.asStateFlow()
    fun clearAttachmentError() { _attachmentError.value = null }

    fun addAttachment(uri: Uri) {
        if (_attachments.value.size >= 3) {
            _attachmentError.value = "最多只能添加 3 张图片"; return
        }
        viewModelScope.launch {
            imageProcessor.validate(uri)
                .onSuccess { info ->
                    val thumb = imageProcessor.generateThumbnail(uri).getOrNull()
                    _attachments.value = _attachments.value + ImageAttachment(uri, info, thumb)
                }
                .onFailure { _attachmentError.value = it.message ?: "无法添加该图片" }
        }
    }

    fun removeAttachment(attachment: ImageAttachment) {
        _attachments.value = _attachments.value - attachment
    }

    /** 统一签名：从 _attachments.value 读取，不再传参 */
    fun send() {
        if (_userInput.value.isBlank() && _attachments.value.isEmpty()) return
        if (_isLoading.value) return

        val userMessage = _userInput.value
        val currentAttachments = _attachments.value
        val modeSnapshot = _currentMode.value

        viewModelScope.launch {
            // 历史里存 ImageAttachment（Uri 显示），不存 Base64
            _conversationHistory.value = _conversationHistory.value + ConversationMessage(
                role = MessageRole.USER, content = userMessage,
                mode = modeSnapshot, attachments = currentAttachments
            )
            _userInput.value = ""
            _attachments.value = emptyList()
            _isLoading.value = true
            _error.value = null

            try {
                val config = configRepository.observeConfig().first()
                if (config.apiKey.isBlank() || config.modelName.isBlank()) {
                    _error.value = "请先在设置中配置 API Key 和模型"; _isLoading.value = false; return@launch
                }
                val adapter = adapterFactory.createAdapter(config)
                val chatMessage = buildMultimodalMessage(userMessage, currentAttachments, modeSnapshot)
                // …沿用现有 sendChatStream + StreamEvent 收集逻辑（Content/Done/Error）…
            } catch (e: Exception) {
                _error.value = "发生错误: ${e.message}"; _isLoading.value = false
            }
        }
    }

    /** 现编码图片为裸 Base64，组装 contentParts；Base64 不留存历史 */
    private suspend fun buildMultimodalMessage(
        text: String, attachments: List<ImageAttachment>, mode: QuickAiMode
    ): ChatMessage {
        val prompt = buildUserMessage(text, mode)   // 复用现有提示词构造
        if (attachments.isEmpty()) return ChatMessage(role = "user", content = prompt)

        val parts = mutableListOf<MessageContent>(MessageContent.Text(prompt))
        attachments.forEach { att ->
            imageProcessor.processForVision(att.uri).onSuccess { p ->
                parts += MessageContent.Image(
                    base64 = imageProcessor.encodeToBase64(p.bytes),
                    mimeType = p.mimeType, width = p.width, height = p.height
                )
            }
            // 单张失败则跳过该图（已在 UI 校验过，这里属兜底）
        }
        return ChatMessage(role = "user", content = prompt, contentParts = parts)
    }
}
```

> 编码/压缩均在 `viewModelScope` + `ImageProcessor` 内部 `Dispatchers.IO` 执行，不阻塞主线程。

---

## 9. 权限管理（已精简）

```xml
<!-- 仅相机路径需要；相册用 Photo Picker，无需任何存储权限 -->
<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-permission android:name="android.permission.CAMERA" />
```

- **去掉** `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE`：`PickVisualMedia` 是系统照片选择器，免权限。
- 相机权限运行时申请（`RequestPermission`），拒绝时 Snackbar 引导去设置。
- **不引入** `accompanist-permissions`（YAGNI：单个相机权限用原生 `ActivityResultContracts` 足够）。

---

## 10. 错误处理（统一 Snackbar）

经 `_attachmentError: StateFlow<String?>`，在 `AiQuickDialog` 收集后用 `SnackbarHost` 展示，`clearAttachmentError()` 复位——与编辑器 `saveError` / `applyError` 同一范式（不要用 Toast）。

| 错误场景 | 文案 |
|---------|------|
| 格式不支持 | 不支持的图片格式，请选择 JPG / PNG / GIF / WEBP |
| 源文件 > 10MB | 图片过大（超过 10MB），请选择更小的图片 |
| 数量超过 3 | 最多只能添加 3 张图片 |
| 相机权限被拒 | 需要相机权限才能拍照，可在系统设置中开启 |
| 读取/解码失败 | 图片读取失败，请重试 |
| 模型不支持视觉（API 4xx） | 当前模型不支持图片分析，请更换支持视觉的模型 |

---

## 11. 测试策略

### 11.1 单元测试（`kotlin.Result` 断言）
- `ImageProcessor`：支持/不支持格式校验；>10MB 拒绝；长边 >1568 时下采样、≤1568 时保持；Base64 输出无 `data:` 前缀且可解码；缩略图尺寸。
- `AiQuickViewModel`：`addAttachment` 更新状态；超 3 张报错；`removeAttachment`；`send()` 在有附件时 `contentParts` 含 `Text + Image(裸 Base64)`。
- 适配器：给定含图 `ChatMessage`，OpenAI 产出 `image_url.url=data:...`、Claude 产出 `source{base64,media_type,data}`（用 `buildJsonObject` 后断言 JSON 结构）。

### 11.2 集成 / UI 测试
- Photo Picker 选择、相机拍照（FileProvider 临时文件）回流。
- 附件预览、删除、消息内缩略图、点击放大。
- OpenAI / Claude 真实多模态请求各跑一次。

---

## 12. 性能优化

- **发送前下采样长边到 1568px**：既防触各家单图上限，又大幅降低 input token 成本（视觉模型按像素计费）。
- **Base64 膨胀 ~33%**：处理后字节 × 1.33 才是入参体积，下采样后通常远低于 5MB。
- **历史不留 Base64**：`ConversationMessage` 只存 `ImageAttachment`（Uri），Base64 现用现弃，避免内存堆积。
- Coil 负责缩略图内存缓存 / 大图磁盘缓存；及时 `recycle()` 自管 Bitmap；`Dispatchers.IO` 跑编码与解码。

---

## 13. 依赖更新

```kotlin
// 均已在 app/build.gradle.kts，无需新增：
implementation(libs.coil.compose)              // 图片加载
implementation(libs.androidx.activity.compose) // rememberLauncherForActivityResult
```

- **不**新增 `accompanist-permissions`（见第 9 节）。
- 新增源文件：`MessageContent.kt`、`ImageProcessor.kt`（外加各适配器 / ViewModel / Dialog 的改动）。

---

## 14. 实现顺序（复用基础设施后工作量下降）

| 阶段 | 内容 | 预估 |
|------|------|------|
| 1.1 模型与处理 | `MessageContent`；扩展 `ChatMessage`；`ImageProcessor`（复用 `ImageRepository` Bitmap 逻辑 + 设置） | 1 天 |
| 1.2 适配器 | OpenAI / Claude `content` 数组化 + JSON 结构单测 | 1 天 |
| 1.3 ViewModel | `_attachments` 状态、`addAttachment`/`removeAttachment`、`send()` + `buildMultimodalMessage` | 1 天 |
| 1.4 UI | Photo Picker / 相机（复用 FileProvider）、预览区、消息内缩略图、视觉提示条 | 2 天 |
| 1.5 测试与打磨 | 单测 + 双 Provider 集成 + 文案/边界 | 1.5 天 |

**总计：约 6–7 天**（v1.0 估 8–12 天；复用现有图片基础设施后下降）。

---

## 15. Phase 2 预览

- **主 AI 助手（Chat / Agent）附件 + 持久化**：扩展 `MessageEntity`（新增 `attachmentsJson` 列）+ Room 迁移；图片以**文件**存入应用私有目录并记录路径（**不**把 Base64 入库）；`ConversationRepository` / 映射器同步。
- **Gemini 视觉**：`GeminiAdapter` 的 `parts` 增加 `inline_data{mimeType, data}`（裸 Base64）；超 20MB 走 Files API。
- 文件管理器选择、非图片文件附件；图片裁剪 / 旋转；附件历史管理。

---

## 16. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| 各家 API 图片格式/上限变化 | 高 | 实现时核对最新文档；图片走 Provider 中立模型，差异收敛在适配器 |
| 模型不支持视觉 | 中 | 不硬阻断，发请求 + 优雅报错 + 非阻断提示（第 6 节） |
| 大图导致内存/超时 | 中 | 源文件 10MB 守卫 + 下采样 1568px + 限 3 张 + IO 线程 |
| OPENAI_COMPATIBLE 模型五花八门 | 中 | 仅尝试 + 报错，不枚举能力 |
| 相机临时文件泄露 | 低 | 复用受控 FileProvider，存 cache 目录定期清理 |

---

## 17. 修订记录

**v1.1（2026-06-16）** — 与真实代码对齐 + 补齐缺口（范围不变）：
1. `Result` 全部改为 `kotlin.Result`（删除 `Result.Success/Failure/.data`）。
2. 适配器 JSON 改用真实的 `buildJsonObject`/`putJsonArray`/`addJsonObject`，并给出 OpenAI / Claude 的真实多模态片段。
3. 图片模型改为 **Provider 中立**（裸 Base64 + mimeType），修掉 v1.0 把 OpenAI data-URL 塞进通用模型、Claude 取错格式的矛盾。
4. 历史中的附件改存 `ImageAttachment`（Uri）而非 Base64，避免内存膨胀。
5. 统一 `send()` 签名（读 `_attachments.value`）。
6. 错误提示统一 **Snackbar**（删除 Toast 表述）。
7. **复用** `ImageRepository` 的解码/缩放/压缩与现有压缩设置；`ImageProcessor` 不落库。
8. 相册改用 **Photo Picker（免权限）**，相机复用现有 **FileProvider**；精简权限、移除 accompanist 依赖。
9. 新增**第 6 节视觉能力识别**策略。
10. 区分「源文件大小 / 发送前长边 / 编码后载荷」，明确 Base64 ~33% 膨胀与各家上限；下采样长边 1568px。
11. 明确划词对话框附件为**内存态**（Phase 1 取舍），主助手持久化划入 Phase 2。

**v1.0（2026-06-15）** — 初版设计（基础图片支持）。

---

## 18. 总结

v1.1 在不扩大范围的前提下，让本设计**与现有代码一致、可直接落地**：复用图片处理与 FileProvider 基础设施、统一 `kotlin.Result` 与 Snackbar 范式、把图片做成 Provider 中立由适配器各自格式化、用免权限的 Photo Picker 简化权限与依赖，并补上视觉能力识别与各家载荷约束。主 AI 助手的持久化附件作为 Phase 2 的清晰衔接点。

**下一步**：如需进入实现，可据本文用 writing-plans 产出实现计划。
