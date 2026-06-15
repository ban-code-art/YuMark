# AI 助手附件支持功能设计文档

**日期**: 2026-06-15  
**版本**: v1.0  
**阶段**: Phase 1 - 基础图片支持

---

## 1. 概述

### 1.1 目标

为 YuMark 的 AI 助手（询问模式和 Agent 模式）添加图片附件支持，使用户能够：
- 上传图片让 AI 进行视觉分析
- 基于图片内容进行对话
- Agent 模式下根据图片内容修改文本

### 1.2 范围

**Phase 1 包含**：
- 图片选择（相册、相机）
- 图片压缩和处理
- 多模态 API 调用（OpenAI、Claude）
- UI：附件预览、缩略图显示
- 最多 3 个附件

**Phase 1 不包含**：
- 文件管理器选择
- Gemini 支持
- 图片编辑功能
- 非图片文件支持

### 1.3 约束

- 单个图片最大 5MB
- 超过 5MB 时自动压缩（保持质量 85%）
- 仅支持常见图片格式（JPG、PNG、GIF、WEBP）
- 附件显示为小缩略图，点击查看大图
- 附件预览在输入框上方

---

## 2. 数据模型设计

### 2.1 消息内容类型

新增 `MessageContent` 封装多模态内容：

```kotlin
// app/src/main/java/com/yumark/app/domain/model/MessageContent.kt

sealed class MessageContent {
    /** 文本内容 */
    data class Text(val text: String) : MessageContent()
    
    /** 图片内容 */
    data class Image(
        val uri: String,           // 本地 URI 或 Base64 数据
        val mimeType: String,      // image/jpeg, image/png, image/gif, image/webp
        val sizeBytes: Long,       // 文件大小（字节）
        val width: Int? = null,    // 图片宽度
        val height: Int? = null    // 图片高度
    ) : MessageContent()
}
```

### 2.2 扩展 ChatMessage

向后兼容地扩展现有 `ChatMessage`：

```kotlin
// app/src/main/java/com/yumark/app/domain/model/AiApiModels.kt

data class ChatMessage(
    val role: String,                                    // "user" | "assistant" | "system"
    val content: String,                                 // 保留（向后兼容纯文本）
    val contentParts: List<MessageContent> = emptyList() // 多模态内容
)
```

**兼容策略**：
- 纯文本消息：只使用 `content`，`contentParts` 为空
- 多模态消息：`contentParts` 包含文本和图片，`content` 包含纯文本部分

### 2.3 对话历史消息

扩展 `ConversationMessage` 支持附件：

```kotlin
// app/src/main/java/com/yumark/app/presentation/editor/AiQuickDialog.kt

data class ConversationMessage(
    val role: MessageRole,
    val content: String,
    val mode: QuickAiMode,
    val attachments: List<MessageContent.Image> = emptyList()  // 新增附件列表
)
```

---

## 3. 图片处理模块

### 3.1 ImageProcessor 工具类

```kotlin
// app/src/main/java/com/yumark/app/core/image/ImageProcessor.kt

class ImageProcessor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val MAX_SIZE_BYTES = 5_000_000L  // 5MB
        const val THUMBNAIL_SIZE = 100          // 缩略图尺寸
        const val COMPRESSION_QUALITY = 85      // 压缩质量
        
        val SUPPORTED_MIME_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp"
        )
    }
    
    /** 验证图片 */
    fun validateImage(uri: Uri): Result<ImageInfo>
    
    /** 压缩图片（如果需要）*/
    suspend fun compressIfNeeded(uri: Uri): Result<ByteArray>
    
    /** 转换为 Base64 */
    fun encodeToBase64(imageBytes: ByteArray, mimeType: String): String
    
    /** 生成缩略图 */
    suspend fun generateThumbnail(uri: Uri): Result<Bitmap>
}

data class ImageInfo(
    val uri: Uri,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int
)
```

### 3.2 压缩策略

```
IF 文件大小 <= 5MB:
    直接读取，不压缩
ELSE:
    计算压缩比例 = 5MB / 实际大小
    使用 Bitmap 压缩
    质量 = 85
    IF 压缩后仍 > 5MB:
        降低分辨率
        再次压缩
```

---

## 4. API 适配器扩展

### 4.1 多模态 API 支持

扩展 `AiApiAdapter` 接口保持不变，在具体实现中处理多模态：

```kotlin
// OpenAI 格式
{
  "model": "gpt-4-vision-preview",
  "messages": [
    {
      "role": "user",
      "content": [
        {"type": "text", "text": "这张图片是什么？"},
        {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,..."}}
      ]
    }
  ]
}

// Claude 格式
{
  "model": "claude-3-opus-20240229",
  "messages": [
    {
      "role": "user",
      "content": [
        {"type": "text", "text": "这张图片是什么？"},
        {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": "..."}}
      ]
    }
  ]
}
```

### 4.2 适配器实现

修改现有适配器：

```kotlin
// app/src/main/java/com/yumark/app/data/ai/adapters/OpenAiAdapter.kt
// app/src/main/java/com/yumark/app/data/ai/adapters/ClaudeAdapter.kt

private fun buildRequestBody(
    messages: List<ChatMessage>,
    config: AiRequestConfig
): JsonObject {
    val formattedMessages = messages.map { message ->
        if (message.contentParts.isEmpty()) {
            // 纯文本消息（旧格式）
            jsonObject {
                put("role", message.role)
                put("content", message.content)
            }
        } else {
            // 多模态消息（新格式）
            jsonObject {
                put("role", message.role)
                put("content", buildArray {
                    message.contentParts.forEach { part ->
                        when (part) {
                            is MessageContent.Text -> add(jsonObject {
                                put("type", "text")
                                put("text", part.text)
                            })
                            is MessageContent.Image -> add(buildImageContent(part))
                        }
                    }
                })
            }
        }
    }
    // ...
}
```

---

## 5. UI 设计

### 5.1 AiQuickDialog 布局

```
┌─────────────────────────────────────┐
│ ✨ AI 助手    [💬 询问] [🤖 处理]    │
├─────────────────────────────────────┤
│ 选中的文本：                        │
│ [文本内容...]                       │
├─────────────────────────────────────┤
│ [对话历史]                          │
│  用户: 文本消息                     │
│  [缩略图1] [缩略图2]  ← 图片附件    │
│  AI: 回复...                        │
├─────────────────────────────────────┤
│ [附件预览区]  ← 新增                │
│  [缩略图] [X] [缩略图] [X]          │
├─────────────────────────────────────┤
│ [输入框]                    [📎][发送]│
│  ↑ 附件按钮                         │
└─────────────────────────────────────┘
```

### 5.2 组件结构

```kotlin
@Composable
fun AiQuickDialog(...) {
    // 现有代码
    
    // 新增状态
    var attachments by remember { mutableStateOf<List<ImageAttachment>>(emptyList()) }
    val imageProcessor = remember { ImageProcessor(context) }
    
    // 图片选择器
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> ... }
    
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success -> ... }
    
    // UI 布局
    Column {
        // ... 现有内容 ...
        
        // 附件预览区（在输入框上方）
        if (attachments.isNotEmpty()) {
            AttachmentPreviewRow(
                attachments = attachments,
                onRemove = { attachment -> ... }
            )
        }
        
        // 输入框
        Row {
            // 附件按钮
            IconButton(onClick = { showAttachmentMenu = true }) {
                Icon(Icons.Default.AttachFile, "添加附件")
            }
            
            OutlinedTextField(...)
            
            IconButton(onClick = { viewModel.send(attachments) }) {
                Icon(Icons.AutoMirrored.Filled.Send, "发送")
            }
        }
    }
}
```

### 5.3 附件选择菜单

```kotlin
@Composable
fun AttachmentMenu(
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onDismiss: () -> Unit
) {
    DropdownMenu(...) {
        DropdownMenuItem(
            text = { Text("从相册选择") },
            leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) },
            onClick = onGallery
        )
        DropdownMenuItem(
            text = { Text("拍照") },
            leadingIcon = { Icon(Icons.Default.CameraAlt, null) },
            onClick = onCamera
        )
    }
}
```

### 5.4 附件预览组件

```kotlin
@Composable
fun AttachmentPreviewRow(
    attachments: List<ImageAttachment>,
    onRemove: (ImageAttachment) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEach { attachment ->
            AttachmentPreviewItem(
                attachment = attachment,
                onRemove = { onRemove(attachment) }
            )
        }
    }
}

@Composable
fun AttachmentPreviewItem(
    attachment: ImageAttachment,
    onRemove: () -> Unit
) {
    Box {
        AsyncImage(
            model = attachment.uri,
            contentDescription = null,
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )
        
        // 删除按钮
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(24.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    CircleShape
                )
        ) {
            Icon(
                Icons.Default.Close,
                "移除",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
```

### 5.5 消息中的附件显示

```kotlin
@Composable
fun MessageBubbleWithAttachments(
    message: ConversationMessage,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // 附件缩略图
        if (message.attachments.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                message.attachments.take(3).forEach { image ->
                    var showFullImage by remember { mutableStateOf(false) }
                    
                    AsyncImage(
                        model = image.uri,
                        contentDescription = null,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { showFullImage = true },
                        contentScale = ContentScale.Crop
                    )
                    
                    // 全图查看对话框
                    if (showFullImage) {
                        ImageViewDialog(
                            imageUri = image.uri,
                            onDismiss = { showFullImage = false }
                        )
                    }
                }
            }
        }
        
        // 文本内容
        MessageBubble(
            message = Message(
                conversationId = "",
                role = message.role,
                content = message.content
            )
        )
    }
}
```

---

## 6. ViewModel 扩展

### 6.1 AiQuickViewModel 更新

```kotlin
class AiQuickViewModel @Inject constructor(...) : ViewModel() {
    
    // 新增状态
    private val _attachments = MutableStateFlow<List<ImageAttachment>>(emptyList())
    val attachments: StateFlow<List<ImageAttachment>> = _attachments.asStateFlow()
    
    private val _attachmentError = MutableStateFlow<String?>(null)
    val attachmentError: StateFlow<String?> = _attachmentError.asStateFlow()
    
    // 添加附件
    fun addAttachment(uri: Uri) {
        viewModelScope.launch {
            if (_attachments.value.size >= 3) {
                _attachmentError.value = "最多只能添加 3 个附件"
                return@launch
            }
            
            // 验证图片
            when (val result = imageProcessor.validateImage(uri)) {
                is Result.Success -> {
                    val info = result.data
                    
                    // 大于 5MB 时尝试压缩验证
                    if (info.sizeBytes > ImageProcessor.MAX_SIZE_BYTES) {
                        // 尝试压缩看是否能降到合理大小
                        val compressed = imageProcessor.compressIfNeeded(uri)
                        if (compressed.isFailure) {
                            _attachmentError.value = "图片过大无法压缩，请选择更小的图片"
                            return@launch
                        }
                    }
                    
                    // 生成缩略图
                    val thumbnail = imageProcessor.generateThumbnail(uri).getOrNull()
                    
                    // 添加到列表
                    _attachments.value = _attachments.value + ImageAttachment(
                        uri = uri,
                        info = info,
                        thumbnail = thumbnail
                    )
                }
                is Result.Failure -> {
                    _attachmentError.value = result.error ?: "不支持的图片格式"
                }
            }
        }
    }
    
    // 移除附件
    fun removeAttachment(attachment: ImageAttachment) {
        _attachments.value = _attachments.value - attachment
    }
    
    // 清空附件错误
    fun clearAttachmentError() {
        _attachmentError.value = null
    }
    
    // 发送消息（扩展）
    fun send() {
        if (_userInput.value.isBlank() && _attachments.value.isEmpty()) return
        if (_isLoading.value) return
        
        val userMessage = _userInput.value
        val currentAttachments = _attachments.value
        val currentModeSnapshot = _currentMode.value
        
        viewModelScope.launch {
            // 添加用户消息到历史
            _conversationHistory.value = _conversationHistory.value + ConversationMessage(
                role = MessageRole.USER,
                content = userMessage,
                mode = currentModeSnapshot,
                attachments = currentAttachments.map { attachment ->
                    MessageContent.Image(
                        uri = attachment.uri.toString(),
                        mimeType = attachment.info.mimeType,
                        sizeBytes = attachment.info.sizeBytes,
                        width = attachment.info.width,
                        height = attachment.info.height
                    )
                }
            )
            
            // 清空输入和附件
            _userInput.value = ""
            _attachments.value = emptyList()
            _isLoading.value = true
            _error.value = null
            
            try {
                val config = configRepository.observeConfig().first()
                val adapter = adapterFactory.createAdapter(config)
                
                // 构建多模态消息
                val chatMessage = buildMultimodalMessage(
                    text = userMessage,
                    attachments = currentAttachments
                )
                
                // 发送请求
                // ...
            } catch (e: Exception) {
                _error.value = "发生错误: ${e.message}"
                _isLoading.value = false
            }
        }
    }
    
    // 构建多模态消息
    private suspend fun buildMultimodalMessage(
        text: String,
        attachments: List<ImageAttachment>
    ): ChatMessage {
        if (attachments.isEmpty()) {
            // 纯文本消息
            return ChatMessage(
                role = "user",
                content = buildUserMessage(text, _currentMode.value)
            )
        }
        
        // 多模态消息
        val contentParts = mutableListOf<MessageContent>()
        
        // 添加文本
        contentParts.add(MessageContent.Text(
            text = buildUserMessage(text, _currentMode.value)
        ))
        
        // 处理并添加图片
        attachments.forEach { attachment ->
            // 压缩图片
            val compressed = imageProcessor.compressIfNeeded(attachment.uri).getOrThrow()
            
            // Base64 编码
            val base64 = imageProcessor.encodeToBase64(compressed, attachment.info.mimeType)
            
            contentParts.add(MessageContent.Image(
                uri = "data:${attachment.info.mimeType};base64,$base64",
                mimeType = attachment.info.mimeType,
                sizeBytes = compressed.size.toLong()
            ))
        }
        
        return ChatMessage(
            role = "user",
            content = text,  // 纯文本部分
            contentParts = contentParts
        )
    }
}

data class ImageAttachment(
    val uri: Uri,
    val info: ImageInfo,
    val thumbnail: Bitmap?
)
```

---

## 7. 权限管理

### 7.1 AndroidManifest.xml

```xml
<!-- 相机权限 -->
<uses-feature
    android:name="android.hardware.camera"
    android:required="false" />
<uses-permission android:name="android.permission.CAMERA" />

<!-- 读取图片权限（Android 13+）-->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />

<!-- 读取外部存储（Android 12 及以下）-->
<uses-permission
    android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
```

### 7.2 运行时权限请求

```kotlin
// 相机权限
val cameraPermission = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
) { granted ->
    if (granted) {
        cameraLauncher.launch(tempImageUri)
    } else {
        // 显示权限说明
    }
}

// 相册权限（Android 13+ 不需要权限）
```

---

## 8. 错误处理

### 8.1 错误场景

| 错误场景 | 处理方式 |
|---------|---------|
| 图片格式不支持 | 显示 Toast："不支持的图片格式，请选择 JPG、PNG、GIF 或 WEBP" |
| 图片超过 5MB | 自动压缩，压缩失败时显示 Toast："图片过大无法压缩，请选择更小的图片" |
| 附件数量超过 3 个 | 显示 Toast："最多只能添加 3 个附件" |
| 权限被拒绝 | 显示对话框，引导用户到设置开启权限 |
| 图片读取失败 | 显示 Toast："图片读取失败，请重试" |
| API 不支持视觉 | 显示提示："当前模型不支持图片分析，请选择支持视觉的模型" |

### 8.2 错误提示组件

```kotlin
// 在 AiQuickDialog 中
attachmentError?.let { error ->
    Snackbar(
        modifier = Modifier.padding(16.dp),
        action = {
            TextButton(onClick = { viewModel.clearAttachmentError() }) {
                Text("知道了")
            }
        }
    ) {
        Text(error)
    }
}
```

---

## 9. 测试策略

### 9.1 单元测试

```kotlin
// ImageProcessorTest.kt
class ImageProcessorTest {
    @Test
    fun `validate image with supported format returns success`()
    
    @Test
    fun `validate image with unsupported format returns failure`()
    
    @Test
    fun `compress image larger than 5MB reduces size`()
    
    @Test
    fun `compress image smaller than 5MB keeps original`()
    
    @Test
    fun `encode to base64 produces valid string`()
    
    @Test
    fun `generate thumbnail creates bitmap with correct size`()
}

// AiQuickViewModelTest.kt
class AiQuickViewModelTest {
    @Test
    fun `add attachment updates state correctly`()
    
    @Test
    fun `add more than 3 attachments shows error`()
    
    @Test
    fun `remove attachment updates state correctly`()
    
    @Test
    fun `send with attachments builds multimodal message`()
}
```

### 9.2 集成测试

- 测试相册选择流程
- 测试相机拍照流程
- 测试附件预览显示
- 测试发送多模态消息
- 测试不同 AI 提供商的兼容性

### 9.3 UI 测试

- 测试附件按钮点击
- 测试附件删除
- 测试消息中附件显示
- 测试图片点击放大

---

## 10. 性能优化

### 10.1 图片加载

- 使用 Coil 库异步加载
- 缩略图使用内存缓存
- 大图使用磁盘缓存

### 10.2 内存管理

- 及时释放 Bitmap 对象
- 使用 WeakReference 持有大对象
- 限制附件数量（最多 3 个）

### 10.3 网络优化

- Base64 编码在后台线程
- 使用 Dispatchers.IO 进行文件操作
- 图片压缩在后台线程

---

## 11. 依赖更新

### 11.1 新增依赖

```kotlin
// app/build.gradle.kts

dependencies {
    // Coil（图片加载，已有）
    implementation(libs.coil.compose)
    
    // 可能需要的：
    // implementation("androidx.activity:activity-compose:1.8.0") // ActivityResultContracts
}
```

### 11.2 权限依赖

使用 Accompanist Permissions 库简化权限管理：

```kotlin
implementation("com.google.accompanist:accompanist-permissions:0.32.0")
```

---

## 12. 实现顺序

### 阶段 1.1：基础架构（1-2 天）
1. 创建 `MessageContent` sealed class
2. 扩展 `ChatMessage` 支持 `contentParts`
3. 创建 `ImageProcessor` 工具类
4. 实现图片验证、压缩、Base64 编码

### 阶段 1.2：UI 组件（2-3 天）
1. 实现附件选择菜单
2. 实现附件预览组件
3. 集成相册和相机选择器
4. 实现权限请求

### 阶段 1.3：ViewModel 集成（1-2 天）
1. 扩展 `AiQuickViewModel` 支持附件
2. 实现多模态消息构建
3. 实现附件状态管理

### 阶段 1.4：API 适配（2-3 天）
1. 修改 `OpenAiAdapter` 支持多模态
2. 修改 `ClaudeAdapter` 支持多模态
3. 测试 API 调用

### 阶段 1.5：测试和优化（2 天）
1. 编写单元测试
2. 进行集成测试
3. 性能优化
4. Bug 修复

**总计：8-12 天**

---

## 13. Phase 2 预览

Phase 2 将包含：
- 文件管理器选择
- Gemini 视觉支持
- 图片编辑功能（裁剪、旋转）
- 更多附件类型探索
- 附件历史管理

---

## 14. 风险和缓解

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| API 限制变化 | 高 | 实现时查阅最新文档，添加版本检测 |
| 内存溢出 | 中 | 严格限制附件数量和大小，及时释放资源 |
| 权限被拒绝 | 中 | 提供清晰的权限说明，优雅降级 |
| 压缩质量问题 | 低 | 允许用户调整压缩质量（未来） |
| 不同设备兼容 | 中 | 在多种设备上测试 |

---

## 15. 总结

本设计文档详细描述了 AI 助手附件支持功能的 Phase 1 实现方案。核心亮点：

1. **向后兼容**：扩展现有模型，不破坏现有功能
2. **模块化设计**：图片处理独立模块，易于测试和维护
3. **良好的用户体验**：自动压缩、清晰的错误提示、流畅的交互
4. **多提供商支持**：统一接口适配不同 AI 提供商
5. **渐进式实现**：分阶段交付，降低风险

实现完成后，用户将能够在 AI 对话中上传图片，让 AI 进行视觉分析和基于图片的文本处理。

---

**下一步**：等待设计评审通过后，进入实现计划阶段。
