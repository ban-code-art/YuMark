# AI 助手附件支持（Phase 1）实现规划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为划词快捷对话框（`AiQuickDialog`）的询问/处理两种模式加入图片附件，支持 OpenAI 与 Claude 的多模态调用。

**Architecture:** 图片在领域模型里以「Provider 中立的裸 Base64 + mimeType」表示（`MessageContent`），由各适配器按自家协议格式化；图片处理（校验/下采样/编码/缩略图）集中在 `ImageProcessor`，复用现有压缩设置；相册用免权限的 Android Photo Picker，相机复用现有 FileProvider；附件状态、发送、错误反馈在 `AiQuickViewModel`，错误经 `StateFlow` → Snackbar。附件仅存在于内存（不落 Room，主助手持久化属 Phase 2）。

**Tech Stack:** Kotlin、Jetpack Compose、Hilt、kotlinx.serialization（JSON 构建）、Ktor（SSE，已有）、Coil（`AsyncImage`，已有）、`androidx.activity` ActivityResult（已有）、`java.util.Base64`（minSdk 26，可单测）。测试：JUnit5 + Truth + MockK + Turbine + kotlinx-coroutines-test。

**对应设计文档：** `docs/superpowers/specs/2026-06-15-ai-attachment-support-design.md`（v1.1）

**构建/测试 JDK：** 本机默认 JDK 11，但项目需 JDK 17。所有 gradle 命令前缀 `JAVA_HOME="C:/Users/luo13/.jdks/jbr-17.0.14"`。

---

## 测试边界说明（务必先读）

项目单测在 JVM 上跑（JUnit5，无 Robolectric、无 Compose UI Test 依赖）。因此：
- **可单测**：纯 Kotlin 逻辑——多模态 JSON 构建、`computeTargetSize`、`encodeToBase64`（用 `java.util.Base64`）、ViewModel 状态（`Uri` 用 `mockk()`、`ImageProcessor` 用 mock）。
- **不可纯单测、改用「编译 + 真机手测」**：`ImageProcessor` 中依赖 `Context`/`ContentResolver`/`Bitmap` 的解码与缩略图、所有 Compose UI、相机/相册回流。任务里会显式标注。

不要为不可单测的部分硬造会失败/无意义的 JVM 测试。

---

## File Structure

**新建：**
- `app/src/main/java/com/yumark/app/domain/model/MessageContent.kt` — 多模态内容 sealed class（Text / Image 裸 Base64）。
- `app/src/main/java/com/yumark/app/data/ai/MultimodalJson.kt` — 纯函数：把 `List<MessageContent>` 构建成 OpenAI / Claude 的 content JsonArray。
- `app/src/main/java/com/yumark/app/core/image/ImageProcessor.kt` — 图片校验/下采样/编码/缩略图 + `ImageInfo`/`ProcessedImage` + 纯函数 `computeTargetSize`/`encodeToBase64`。
- `app/src/test/java/com/yumark/app/data/ai/MultimodalJsonTest.kt`
- `app/src/test/java/com/yumark/app/core/image/ImageProcessorPureTest.kt`
- `app/src/test/java/com/yumark/app/presentation/editor/AiQuickViewModelTest.kt`

**修改：**
- `app/src/main/java/com/yumark/app/domain/model/AiApiModels.kt` — `ChatMessage` 增 `contentParts`。
- `app/src/main/java/com/yumark/app/data/ai/adapters/OpenAiAdapter.kt` — content 数组化。
- `app/src/main/java/com/yumark/app/data/ai/adapters/ClaudeAdapter.kt` — content 数组化。
- `app/src/main/java/com/yumark/app/presentation/editor/AiQuickDialog.kt` — VM 注入 `ImageProcessor`、附件状态/方法、`send()` 多模态、`ConversationMessage.attachments`、UI（附件按钮/选择器/预览/消息缩略图/视觉提示/Snackbar）。
- `app/src/main/AndroidManifest.xml` — 相机 feature + 权限。
- `app/src/main/res/xml/file_paths.xml` — 相机临时文件 cache-path。

---

## Task 1: 多模态领域模型 + JSON 构建器（TDD）

**Files:**
- Create: `app/src/main/java/com/yumark/app/domain/model/MessageContent.kt`
- Modify: `app/src/main/java/com/yumark/app/domain/model/AiApiModels.kt`
- Create: `app/src/main/java/com/yumark/app/data/ai/MultimodalJson.kt`
- Test: `app/src/test/java/com/yumark/app/data/ai/MultimodalJsonTest.kt`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/yumark/app/data/ai/MultimodalJsonTest.kt`
```kotlin
package com.yumark.app.data.ai

import com.google.common.truth.Truth.assertThat
import com.yumark.app.domain.model.MessageContent
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class MultimodalJsonTest {

    private val parts = listOf(
        MessageContent.Text("看看这张图"),
        MessageContent.Image(base64 = "QUJD", mimeType = "image/png")
    )

    @Test
    fun `openAi content array uses image_url data url`() {
        val arr = openAiContentArray(parts)
        // 第 0 项是文本
        assertThat(arr[0].jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("text")
        assertThat(arr[0].jsonObject["text"]!!.jsonPrimitive.content).isEqualTo("看看这张图")
        // 第 1 项是 image_url，url 为 data URL
        assertThat(arr[1].jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("image_url")
        val url = arr[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertThat(url).isEqualTo("data:image/png;base64,QUJD")
    }

    @Test
    fun `claude content array uses base64 source without prefix`() {
        val arr = claudeContentArray(parts)
        assertThat(arr[0].jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("text")
        val img = arr[1].jsonObject
        assertThat(img["type"]!!.jsonPrimitive.content).isEqualTo("image")
        val source = img["source"]!!.jsonObject
        assertThat(source["type"]!!.jsonPrimitive.content).isEqualTo("base64")
        assertThat(source["media_type"]!!.jsonPrimitive.content).isEqualTo("image/png")
        // 裸 Base64，无 data: 前缀
        assertThat(source["data"]!!.jsonPrimitive.content).isEqualTo("QUJD")
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `JAVA_HOME="C:/Users/luo13/.jdks/jbr-17.0.14" ./gradlew :app:testDebugUnitTest --tests "com.yumark.app.data.ai.MultimodalJsonTest"`
Expected: 编译失败（`MessageContent`、`openAiContentArray`、`claudeContentArray` 未定义）。

- [ ] **Step 3: 建 MessageContent 模型**

`app/src/main/java/com/yumark/app/domain/model/MessageContent.kt`
```kotlin
package com.yumark.app.domain.model

/** AI 多模态消息内容片段 */
sealed class MessageContent {
    /** 文本片段 */
    data class Text(val text: String) : MessageContent()

    /**
     * 图片片段——Provider 中立。
     * 只存裸 Base64（无 "data:..." 前缀）+ mimeType；由各适配器按自家协议格式化。
     */
    data class Image(
        val base64: String,
        val mimeType: String,
        val width: Int? = null,
        val height: Int? = null
    ) : MessageContent()
}
```

- [ ] **Step 4: 扩展 ChatMessage**

`app/src/main/java/com/yumark/app/domain/model/AiApiModels.kt`，把
```kotlin
data class ChatMessage(
    val role: String,  // "user" | "assistant" | "system"
    val content: String
)
```
改为
```kotlin
data class ChatMessage(
    val role: String,                                     // "user" | "assistant" | "system"
    val content: String,                                  // 纯文本路径仍只用它
    val contentParts: List<MessageContent> = emptyList()  // 非空即走多模态
)
```

- [ ] **Step 5: 实现 JSON 构建器**

`app/src/main/java/com/yumark/app/data/ai/MultimodalJson.kt`
```kotlin
package com.yumark.app.data.ai

import com.yumark.app.domain.model.MessageContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** OpenAI /chat/completions 的 content 数组：图片用 image_url + data URL */
fun openAiContentArray(parts: List<MessageContent>): JsonArray = buildJsonArray {
    parts.forEach { part ->
        when (part) {
            is MessageContent.Text -> addJsonObject {
                put("type", "text"); put("text", part.text)
            }
            is MessageContent.Image -> addJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") {
                    put("url", "data:${part.mimeType};base64,${part.base64}")
                }
            }
        }
    }
}

/** Claude Messages 的 content 数组：图片用 source.base64（裸 Base64 + media_type） */
fun claudeContentArray(parts: List<MessageContent>): JsonArray = buildJsonArray {
    parts.forEach { part ->
        when (part) {
            is MessageContent.Text -> addJsonObject {
                put("type", "text"); put("text", part.text)
            }
            is MessageContent.Image -> addJsonObject {
                put("type", "image")
                putJsonObject("source") {
                    put("type", "base64")
                    put("media_type", part.mimeType)
                    put("data", part.base64)
                }
            }
        }
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `JAVA_HOME="C:/Users/luo13/.jdks/jbr-17.0.14" ./gradlew :app:testDebugUnitTest --tests "com.yumark.app.data.ai.MultimodalJsonTest"`
Expected: PASS（2 个测试）。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/yumark/app/domain/model/MessageContent.kt \
        app/src/main/java/com/yumark/app/domain/model/AiApiModels.kt \
        app/src/main/java/com/yumark/app/data/ai/MultimodalJson.kt \
        app/src/test/java/com/yumark/app/data/ai/MultimodalJsonTest.kt
git commit -m "feat(ai): add provider-neutral multimodal content model and JSON builders"
```

---

## Task 2: 适配器接入多模态（OpenAI + Claude）

**Files:**
- Modify: `app/src/main/java/com/yumark/app/data/ai/adapters/OpenAiAdapter.kt`
- Modify: `app/src/main/java/com/yumark/app/data/ai/adapters/ClaudeAdapter.kt`

> 适配器内嵌 HttpClient 流，无法纯单测；正确性由 Task 1 的构建器单测保证，这里只做接入 + 编译验证。

- [ ] **Step 1: 改 OpenAiAdapter 的 messages 构建**

`OpenAiAdapter.kt`，把 `putJsonArray("messages")` 块里的
```kotlin
messages.forEach { m ->
    addJsonObject { put("role", m.role); put("content", m.content) }
}
```
改为
```kotlin
messages.forEach { m ->
    addJsonObject {
        put("role", m.role)
        if (m.contentParts.isEmpty()) {
            put("content", m.content)
        } else {
            put("content", com.yumark.app.data.ai.openAiContentArray(m.contentParts))
        }
    }
}
```
（`put(key, JsonElement)` 重载来自 `kotlinx.serialization.json`，`JsonArray` 是 `JsonElement`，无需新增 import；若 IDE 提示，确保已 `import kotlinx.serialization.json.put`——文件已存在该 import。）

- [ ] **Step 2: 改 ClaudeAdapter 的 messages 构建**

`ClaudeAdapter.kt`，把
```kotlin
messages.filter { it.role != "system" }.forEach { m ->
    addJsonObject { put("role", m.role); put("content", m.content) }
}
```
改为
```kotlin
messages.filter { it.role != "system" }.forEach { m ->
    addJsonObject {
        put("role", m.role)
        if (m.contentParts.isEmpty()) {
            put("content", m.content)
        } else {
            put("content", com.yumark.app.data.ai.claudeContentArray(m.contentParts))
        }
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `JAVA_HOME="C:/Users/luo13/.jdks/jbr-17.0.14" ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/yumark/app/data/ai/adapters/OpenAiAdapter.kt \
        app/src/main/java/com/yumark/app/data/ai/adapters/ClaudeAdapter.kt
git commit -m "feat(ai): wire multimodal content into OpenAI and Claude adapters"
```

---

## Task 3: ImageProcessor（纯函数 TDD + Android 方法）

**Files:**
- Create: `app/src/main/java/com/yumark/app/core/image/ImageProcessor.kt`
- Test: `app/src/test/java/com/yumark/app/core/image/ImageProcessorPureTest.kt`

- [ ] **Step 1: 写失败测试（纯函数）**

`app/src/test/java/com/yumark/app/core/image/ImageProcessorPureTest.kt`
```kotlin
package com.yumark.app.core.image

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ImageProcessorPureTest {

    @Test
    fun `computeTargetSize downscales long edge keeping ratio`() {
        // 4000x2000，长边限 1568 -> 1568x784
        val (w, h) = computeTargetSize(4000, 2000, 1568)
        assertThat(w).isEqualTo(1568)
        assertThat(h).isEqualTo(784)
    }

    @Test
    fun `computeTargetSize keeps size when within limit`() {
        val (w, h) = computeTargetSize(1000, 800, 1568)
        assertThat(w).isEqualTo(1000)
        assertThat(h).isEqualTo(800)
    }

    @Test
    fun `computeTargetSize handles portrait long edge`() {
        val (w, h) = computeTargetSize(2000, 4000, 1568)
        assertThat(w).isEqualTo(784)
        assertThat(h).isEqualTo(1568)
    }

    @Test
    fun `encodeToBase64 has no data prefix and round-trips`() {
        val bytes = byteArrayOf(65, 66, 67) // "ABC"
        val b64 = encodeToBase64(bytes)
        assertThat(b64).doesNotContain("data:")
        assertThat(b64).isEqualTo("QUJD")
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME="C:/Users/luo13/.jdks/jbr-17.0.14" ./gradlew :app:testDebugUnitTest --tests "com.yumark.app.core.image.ImageProcessorPureTest"`
Expected: 编译失败（`computeTargetSize`、`encodeToBase64` 未定义）。

- [ ] **Step 3: 实现 ImageProcessor（含纯函数）**

`app/src/main/java/com/yumark/app/core/image/ImageProcessor.kt`
```kotlin
package com.yumark.app.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.yumark.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

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

/** 等比把长边缩到 maxEdge；已在范围内则原样返回。纯函数，可单测。 */
internal fun computeTargetSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    val longEdge = maxOf(width, height)
    if (longEdge <= maxEdge || longEdge == 0) return width to height
    val ratio = maxEdge.toFloat() / longEdge
    return (width * ratio).toInt().coerceAtLeast(1) to (height * ratio).toInt().coerceAtLeast(1)
}

/** 裸 Base64（无 data: 前缀）。用 java.util.Base64（minSdk 26 可用，且 JVM 可单测）。 */
internal fun encodeToBase64(bytes: ByteArray): String =
    java.util.Base64.getEncoder().encodeToString(bytes)

@Singleton
class ImageProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        const val MAX_SOURCE_BYTES = 10_000_000L
        const val VISION_MAX_EDGE = 1568
        const val THUMBNAIL_MAX_EDGE = 200
        val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
    }

    /** 校验格式与源文件大小 */
    suspend fun validate(uri: Uri): Result<ImageInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            require(mime in SUPPORTED_MIME_TYPES) { "不支持的图片格式，请选择 JPG / PNG / GIF / WEBP" }

            val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                ?: context.contentResolver.openInputStream(uri)?.use { it.available().toLong() }
                ?: 0L
            require(size <= MAX_SOURCE_BYTES) { "图片过大（超过 10MB），请选择更小的图片" }

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ImageInfo(uri, mime, size, opts.outWidth.coerceAtLeast(0), opts.outHeight.coerceAtLeast(0))
        }
    }

    /**
     * 视觉用途处理：GIF 直接读原字节（保动画）；其余解码 → 按需下采样长边 → 压缩为字节流。
     * 质量取自设置（imageCompressionQuality），回退 85。
     */
    suspend fun processForVision(uri: Uri): Result<ProcessedImage> = withContext(Dispatchers.IO) {
        runCatching {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            if (mime == "image/gif") {
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                return@runCatching ProcessedImage(bytes, mime, opts.outWidth, opts.outHeight)
            }

            val src = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
                ?: error("无法解码图片")
            val (tw, th) = computeTargetSize(src.width, src.height, VISION_MAX_EDGE)
            val scaled = if (tw != src.width || th != src.height)
                Bitmap.createScaledBitmap(src, tw, th, true).also { src.recycle() } else src

            val settings = settingsRepository.getSettings()
            val quality = settings.imageCompressionQuality.value.coerceIn(1, 100)
            val format = if (mime == "image/png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val outMime = if (mime == "image/png") "image/png" else "image/jpeg"

            val out = ByteArrayOutputStream()
            scaled.compress(format, quality, out)
            val result = ProcessedImage(out.toByteArray(), outMime, scaled.width, scaled.height)
            scaled.recycle()
            result
        }
    }

    fun encodeToBase64(bytes: ByteArray): String = com.yumark.app.core.image.encodeToBase64(bytes)

    /** 生成缩略图（用于附件预览）。 */
    suspend fun generateThumbnail(uri: Uri): Result<Bitmap> = withContext(Dispatchers.IO) {
        runCatching {
            val src = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
                ?: error("无法解码图片")
            val (tw, th) = computeTargetSize(src.width, src.height, THUMBNAIL_MAX_EDGE)
            Bitmap.createScaledBitmap(src, tw, th, true).also { if (it !== src) src.recycle() }
        }
    }
}
```

> 注：`ImageProcessor` 用 `@Inject constructor` + `@ApplicationContext` + `SettingsRepository`（已在 `RepositoryModule` 绑定），Hilt 可直接构造，无需新增 module。

- [ ] **Step 4: 运行纯函数测试确认通过**

Run: `JAVA_HOME="C:/Users/luo13/.jdks/jbr-17.0.14" ./gradlew :app:testDebugUnitTest --tests "com.yumark.app.core.image.ImageProcessorPureTest"`
Expected: PASS（4 个测试）。

- [ ] **Step 5: 编译整体确认 Android 方法无误**

Run: `JAVA_HOME="C:/Users/luo13/.jdks/jbr-17.0.14" ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。（Bitmap/ContentResolver 路径靠 Task 8 真机手测验证。）

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/yumark/app/core/image/ImageProcessor.kt \
        app/src/test/java/com/yumark/app/core/image/ImageProcessorPureTest.kt
git commit -m "feat(image): add ImageProcessor with downscale/base64 (pure parts tested)"
```

---

## Task 4: AiQuickViewModel 附件状态（TDD）

**Files:**
- Modify: `app/src/main/java/com/yumark/app/presentation/editor/AiQuickDialog.kt`
- Test: `app/src/test/java/com/yumark/app/presentation/editor/AiQuickViewModelTest.kt`

- [ ] **Step 1: 给 ConversationMessage 加 attachments + ImageAttachment + VM 注入**

在 `AiQuickDialog.kt`：

ConversationMessage 改为：
```kotlin
data class ConversationMessage(
    val role: MessageRole,
    val content: String,
    val mode: QuickAiMode,
    val attachments: List<ImageAttachment> = emptyList()
)
```

在 `AiQuickViewModel` 之上新增数据类（与 VM 同文件）：
```kotlin
data class ImageAttachment(
    val uri: android.net.Uri,
    val info: com.yumark.app.core.image.ImageInfo,
    val thumbnail: android.graphics.Bitmap? = null
)
```

`AiQuickViewModel` 构造函数加注入：
```kotlin
@HiltViewModel
class AiQuickViewModel @Inject constructor(
    private val configRepository: AiConfigRepository,
    private val adapterFactory: AiAdapterFactory,
    private val imageProcessor: com.yumark.app.core.image.ImageProcessor
) : ViewModel() {
```

- [ ] **Step 2: 写失败测试**

`app/src/test/java/com/yumark/app/presentation/editor/AiQuickViewModelTest.kt`
```kotlin
package com.yumark.app.presentation.editor

import android.net.Uri
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.yumark.app.core.image.ImageInfo
import com.yumark.app.core.image.ImageProcessor
import com.yumark.app.data.ai.AiAdapterFactory
import com.yumark.app.domain.repository.AiConfigRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiQuickViewModelTest {

    private val configRepository: AiConfigRepository = mockk(relaxed = true)
    private val adapterFactory: AiAdapterFactory = mockk(relaxed = true)
    private val imageProcessor: ImageProcessor = mockk()
    private val testDispatcher = StandardTestDispatcher()

    private fun vm() = AiQuickViewModel(configRepository, adapterFactory, imageProcessor)

    private fun fakeInfo(uri: Uri) = ImageInfo(uri, "image/png", 1000L, 100, 100)

    @BeforeEach fun setup() { Dispatchers.setMain(testDispatcher) }
    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `addAttachment adds on success`() = runTest {
        val uri = mockk<Uri>()
        coEvery { imageProcessor.validate(uri) } returns Result.success(fakeInfo(uri))
        coEvery { imageProcessor.generateThumbnail(uri) } returns Result.failure(Exception("skip"))

        val viewModel = vm()
        viewModel.addAttachment(uri)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.attachments.test {
            assertThat(awaitItem()).hasSize(1)
        }
    }

    @Test
    fun `addAttachment beyond 3 sets error`() = runTest {
        val viewModel = vm()
        repeat(4) { i ->
            val uri = mockk<Uri>()
            coEvery { imageProcessor.validate(uri) } returns Result.success(fakeInfo(uri))
            coEvery { imageProcessor.generateThumbnail(uri) } returns Result.failure(Exception("skip"))
            viewModel.addAttachment(uri)
            testDispatcher.scheduler.advanceUntilIdle()
        }
        viewModel.attachments.test { assertThat(awaitItem()).hasSize(3) }
        viewModel.attachmentError.test { assertThat(awaitItem()).isNotNull() }
    }

    @Test
    fun `addAttachment validation failure sets error not attachment`() = runTest {
        val uri = mockk<Uri>()
        coEvery { imageProcessor.validate(uri) } returns Result.failure(Exception("不支持的图片格式"))

        val viewModel = vm()
        viewModel.addAttachment(uri)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.attachments.test { assertThat(awaitItem()).isEmpty() }
        viewModel.attachmentError.test { assertThat(awaitItem()).isEqualTo("不支持的图片格式") }
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `JAVA_HOME="C:/Users/luo13/.jdks/jbr-17.0.14" ./gradlew :app:testDebugUnitTest --tests "com.yumark.app.presentation.editor.AiQuickViewModelTest"`
Expected: 编译失败（`addAttachment`/`attachments`/`attachmentError` 未定义）。

- [ ] **Step 4: 实现附件状态与方法**

在 `AiQuickViewModel` 内（紧邻其它 `_xxx` 状态之后）加入：
```kotlin
    private val _attachments = MutableStateFlow<List<ImageAttachment>>(emptyList())
    val attachments: StateFlow<List<ImageAttachment>> = _attachments.asStateFlow()

    private val _attachmentError = MutableStateFlow<String?>(null)
    val attachmentError: StateFlow<String?> = _attachmentError.asStateFlow()

    fun clearAttachmentError() { _attachmentError.value = null }

    fun addAttachment(uri: android.net.Uri) {
        if (_attachments.value.size >= 3) {
            _attachmentError.value = "最多只能添加 3 张图片"
            return
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
```
（`MutableStateFlow`/`StateFlow`/`asStateFlow`/`viewModelScope`/`launch` 均已在文件 import。）

- [ ] **Step 5: 运行确认通过**

Run: `JAVA_HOME="C:/Users/luo13/.jdks/jbr-17.0.14" ./gradlew :app:testDebugUnitTest --tests "com.yumark.app.presentation.editor.AiQuickViewModelTest"`
Expected: PASS（3 个测试）。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/yumark/app/presentation/editor/AiQuickDialog.kt \
        app/src/test/java/com/yumark/app/presentation/editor/AiQuickViewModelTest.kt
git commit -m "feat(ai): attachment state management in AiQuickViewModel"
```

---

## Task 5: send() 多模态集成（TDD）

**Files:**
- Modify: `app/src/main/java/com/yumark/app/presentation/editor/AiQuickDialog.kt`
- Test: `app/src/test/java/com/yumark/app/presentation/editor/AiQuickViewModelTest.kt`（追加）

- [ ] **Step 1: 追加失败测试（捕获发送的 ChatMessage）**

在 `AiQuickViewModelTest` 中追加 import 与测试：
```kotlin
// 顶部追加：
import com.yumark.app.data.ai.AiApiAdapter
import com.yumark.app.domain.model.AiConfig
import com.yumark.app.domain.model.ChatMessage
import com.yumark.app.domain.model.MessageContent
import com.yumark.app.domain.model.StreamEvent
import com.yumark.app.core.image.ProcessedImage
import io.mockk.every
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
```
```kotlin
    @Test
    fun `send with attachment builds multimodal ChatMessage`() = runTest {
        val uri = mockk<Uri>()
        coEvery { imageProcessor.validate(uri) } returns Result.success(fakeInfo(uri))
        coEvery { imageProcessor.generateThumbnail(uri) } returns Result.failure(Exception("skip"))
        coEvery { imageProcessor.processForVision(uri) } returns
            Result.success(ProcessedImage(byteArrayOf(1, 2, 3), "image/png", 100, 100))
        every { imageProcessor.encodeToBase64(any()) } returns "QUJD"

        val config = AiConfig(apiKey = "k", modelName = "gpt-4o")
        every { configRepository.observeConfig() } returns flowOf(config)

        val adapter: AiApiAdapter = mockk()
        every { adapterFactory.createAdapter(config) } returns adapter
        val sent = slot<List<ChatMessage>>()
        every { adapter.sendChatStream(capture(sent), any()) } returns flowOf(StreamEvent.Done("ok"))

        val viewModel = vm()
        viewModel.onOpen("选中文本", QuickAiMode.AI_QUERY)
        viewModel.updateUserInput("这张图是什么")
        viewModel.addAttachment(uri)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.send()
        testDispatcher.scheduler.advanceUntilIdle()

        val parts = sent.captured.first().contentParts
        assertThat(parts.any { it is MessageContent.Text }).isTrue()
        val img = parts.filterIsInstance<MessageContent.Image>().first()
        assertThat(img.base64).isEqualTo("QUJD")
        assertThat(img.mimeType).isEqualTo("image/png")
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME="C:/Users/luo13/.jdks/jbr-17.0.14" ./gradlew :app:testDebugUnitTest --tests "com.yumark.app.presentation.editor.AiQuickViewModelTest"`
Expected: 新测试失败（`send()` 尚未携带附件 / 无 `buildMultimodalMessage`）。

- [ ] **Step 3: 改 send() 并新增 buildMultimodalMessage**

在 `AiQuickDialog.kt` 的 `send()`：
1. 函数开头守卫改为允许「有附件也可发」：
```kotlin
    fun send() {
        if ((_userInput.value.isBlank() && _attachments.value.isEmpty()) || _isLoading.value) return

        val userMessage = _userInput.value
        val currentModeSnapshot = _currentMode.value
        val currentAttachments = _attachments.value
```
2. 用户消息入历史时带 attachments，并清空附件：
```kotlin
            _conversationHistory.value = _conversationHistory.value + ConversationMessage(
                role = MessageRole.USER,
                content = userMessage,
                mode = currentModeSnapshot,
                attachments = currentAttachments
            )

            _userInput.value = ""
            _attachments.value = emptyList()
            _isLoading.value = true
            _error.value = null
```
3. 把原来的
```kotlin
                val fullUserMessage = buildUserMessage(userMessage, currentModeSnapshot)
                val messages = listOf(
                    ChatMessage(role = "user", content = fullUserMessage)
                )
```
改为
```kotlin
                val messages = listOf(
                    buildMultimodalMessage(userMessage, currentAttachments, currentModeSnapshot)
                )
```
4. 在 `buildUserMessage` 之后新增：
```kotlin
    private suspend fun buildMultimodalMessage(
        userMessage: String,
        attachments: List<ImageAttachment>,
        mode: QuickAiMode
    ): ChatMessage {
        val prompt = buildUserMessage(userMessage, mode)
        if (attachments.isEmpty()) return ChatMessage(role = "user", content = prompt)

        val parts = mutableListOf<MessageContent>(MessageContent.Text(prompt))
        attachments.forEach { att ->
            imageProcessor.processForVision(att.uri).onSuccess { p ->
                parts += MessageContent.Image(
                    base64 = imageProcessor.encodeToBase64(p.bytes),
                    mimeType = p.mimeType, width = p.width, height = p.height
                )
            }
        }
        return ChatMessage(role = "user", content = prompt, contentParts = parts)
    }
```
5. 顶部确保有 import：`import com.yumark.app.domain.model.MessageContent`。

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME="C:/Users/luo13/.jdks/jbr-17.0.14" ./gradlew :app:testDebugUnitTest --tests "com.yumark.app.presentation.editor.AiQuickViewModelTest"`
Expected: PASS（4 个测试，含新加的）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/yumark/app/presentation/editor/AiQuickDialog.kt \
        app/src/test/java/com/yumark/app/presentation/editor/AiQuickViewModelTest.kt
git commit -m "feat(ai): send multimodal message with image attachments"
```

---

## Task 6: UI —— 附件按钮/选择器/预览/消息缩略图/视觉提示/Snackbar

**Files:**
- Modify: `app/src/main/java/com/yumark/app/presentation/editor/AiQuickDialog.kt`

> Compose、相机/相册回流无单测依赖；本任务靠 Task 8 真机手测验证，本步只保证编译通过。

- [ ] **Step 1: 顶部追加 import**

`AiQuickDialog.kt` 顶部加入：
```kotlin
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import java.io.File
```

- [ ] **Step 2: 相机临时 URI 帮助函数（文件底部）**

`AiQuickDialog.kt` 文件末尾追加：
```kotlin
private fun createCameraTempUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File.createTempFile("att_", ".jpg", dir)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun modelLikelySupportsVision(modelName: String): Boolean {
    val m = modelName.lowercase()
    return listOf(
        "gpt-4o", "gpt-4.1", "gpt-4-vision", "o1", "o3", "o4",
        "claude-3", "claude-4", "claude-opus", "claude-sonnet", "claude-haiku",
        "vision", "-vl", "llava", "qwen-vl", "gemini"
    ).any { m.contains(it) }
}
```

- [ ] **Step 3: 对话框内收集附件状态 + 选择器 launcher**

在 `AiQuickDialog` 组合函数内、`val listState = rememberLazyListState()` 附近加入：
```kotlin
    val context = LocalContext.current
    val attachments by viewModel.attachments.collectAsState()
    val attachmentError by viewModel.attachmentError.collectAsState()
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) viewModel.addAttachment(uri) }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success -> if (success) pendingCameraUri?.let { viewModel.addAttachment(it) } }

    val requestCameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val uri = createCameraTempUri(context)
            pendingCameraUri = uri
            takePicture.launch(uri)
        }
    }
```

- [ ] **Step 4: 错误 Snackbar（复用 ModalBottomSheet 内的 Column）**

在对话框 `Column` 内最顶部（标题 Row 之前）插入：
```kotlin
            val attachmentSnackbar = remember { SnackbarHostState() }
            LaunchedEffect(attachmentError) {
                attachmentError?.let {
                    attachmentSnackbar.showSnackbar(it)
                    viewModel.clearAttachmentError()
                }
            }
            SnackbarHost(attachmentSnackbar)
```
（`SnackbarHost`/`SnackbarHostState`/`LaunchedEffect` 来自 material3/runtime，确认已 import：`import androidx.compose.material3.SnackbarHost`、`import androidx.compose.material3.SnackbarHostState`。）

- [ ] **Step 5: 消息内附件缩略图**

把对话历史 `items(conversationHistory) { message -> MessageBubble(...) }` 替换为：
```kotlin
                items(conversationHistory) { message ->
                    if (message.attachments.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            message.attachments.take(3).forEach { att ->
                                AsyncImage(
                                    model = att.uri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                            }
                        }
                    }
                    MessageBubble(
                        message = Message(
                            conversationId = "",
                            role = message.role,
                            content = message.content
                        ),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
```

- [ ] **Step 6: 附件预览区 + 视觉提示（输入区上方）**

在底部输入 `Column`（`HorizontalDivider()` 之后、输入 `OutlinedTextField` 之前）插入：
```kotlin
                if (attachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        attachments.forEach { att ->
                            Box {
                                AsyncImage(
                                    model = att.uri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                )
                                IconButton(
                                    onClick = { viewModel.removeAttachment(att) },
                                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, "移除", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
```
紧接其后（视觉提示，可选展示）：
```kotlin
                val cfgModel by viewModel.currentModelName.collectAsState()
                if (attachments.isNotEmpty() && cfgModel.isNotBlank() && !modelLikelySupportsVision(cfgModel)) {
                    Text(
                        "当前模型可能不支持图片，可继续尝试或更换模型",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
```
为支撑 `currentModelName`，在 `AiQuickViewModel` 增加：
```kotlin
    val currentModelName: StateFlow<String> = configRepository.observeConfig()
        .map { it.modelName }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, "")
```
（`map`/`stateIn` 已在 Task 3 之外的修订引入；若未 import，加 `import kotlinx.coroutines.flow.map`、`import kotlinx.coroutines.flow.stateIn`。）

- [ ] **Step 7: 输入行加附件按钮 + 菜单**

把输入 `OutlinedTextField` 外层包一个 `Row`，在其左侧加附件按钮：
```kotlin
                Row(verticalAlignment = Alignment.Bottom) {
                    Box {
                        IconButton(onClick = { showAttachmentMenu = true }, enabled = !isLoading) {
                            Icon(Icons.Default.AttachFile, "添加附件")
                        }
                        DropdownMenu(
                            expanded = showAttachmentMenu,
                            onDismissRequest = { showAttachmentMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("从相册选择") },
                                leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) },
                                onClick = {
                                    showAttachmentMenu = false
                                    pickMedia.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("拍照") },
                                leadingIcon = { Icon(Icons.Default.CameraAlt, null) },
                                onClick = {
                                    showAttachmentMenu = false
                                    requestCameraPermission.launch(android.Manifest.permission.CAMERA)
                                }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = userInput,
                        onValueChange = { viewModel.updateUserInput(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                if (currentMode == QuickAiMode.AI_QUERY) "输入你的问题..."
                                else "例如：改写成更专业的表达"
                            )
                        },
                        minLines = 1, maxLines = 3, enabled = !isLoading,
                        trailingIcon = {
                            if (!isLoading && (userInput.isNotBlank() || attachments.isNotEmpty())) {
                                IconButton(onClick = { viewModel.send() }) {
                                    Icon(Icons.AutoMirrored.Filled.Send, "发送")
                                }
                            }
                        }
                    )
                }
```
（删除原来单独的 `OutlinedTextField(...)` 块，由上面的 Row 取代。）

- [ ] **Step 8: 编译验证**

Run: `JAVA_HOME="C:/Users/luo13/.jdks/jbr-17.0.14" ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 9: 提交**

```bash
git add app/src/main/java/com/yumark/app/presentation/editor/AiQuickDialog.kt
git commit -m "feat(ai): attachment UI (picker, preview, thumbnails, vision hint)"
```

---

## Task 7: 权限与 FileProvider 路径

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/xml/file_paths.xml`

- [ ] **Step 1: Manifest 加相机 feature + 权限**

`AndroidManifest.xml`，在现有 `<uses-permission ... INTERNET />` 之后加入：
```xml
    <uses-feature android:name="android.hardware.camera" android:required="false" />
    <uses-permission android:name="android.permission.CAMERA" />
```
（**不**加 READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE——相册走 Photo Picker 免权限。）

- [ ] **Step 2: file_paths.xml 加相机临时目录**

`app/src/main/res/xml/file_paths.xml`，在 `<paths>` 内加入：
```xml
    <cache-path name="camera_temp" path="camera/" />
```
（与 `createCameraTempUri` 写入的 `context.cacheDir/camera/` 对应。）

- [ ] **Step 3: 编译验证**

Run: `JAVA_HOME="C:/Users/luo13/.jdks/jbr-17.0.14" ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/xml/file_paths.xml
git commit -m "chore: camera permission and FileProvider cache path for attachments"
```

---

## Task 8: 全量构建 + 真机手测

**Files:** 无（验证任务）

- [ ] **Step 1: 跑全部单测**

Run: `JAVA_HOME="C:/Users/luo13/.jdks/jbr-17.0.14" ./gradlew :app:testDebugUnitTest`
Expected: 全绿（含 Task 1/3/4/5 新增测试）。

- [ ] **Step 2: 打 Debug 包**

Run: `JAVA_HOME="C:/Users/luo13/.jdks/jbr-17.0.14" ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL，产物 `app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **Step 3: 真机手测清单**（装 APK 后逐项验证）

1. 设置里配置一个**支持视觉**的模型（如 OpenAI `gpt-4o` 或 Claude `claude-sonnet-4-6`）。
2. 编辑器中选中一段文字 → AI 助手快捷对话框打开。
3. 点 📎 → 「从相册选择」→ 选 1 张图（**应无权限弹窗**，系统 Photo Picker 直接出现）。
4. 预览区出现缩略图，右上角 ✕ 可删除。
5. 输入「这张图里有什么」→ 发送 → AI 给出基于图片的回答；用户气泡上方显示缩略图。
6. 切到「处理」模式 + 选图，给修改指令 → 「应用最新修改」生效。
7. 点 📎 → 「拍照」→ 首次弹**相机权限**；允许后拍照回流为附件。
8. 添加第 4 张图 → Snackbar 提示「最多只能添加 3 张图片」。
9. 选一个**不支持视觉**的模型名（如 `deepseek-chat`）+ 加图 → 显示「当前模型可能不支持图片」提示；发送后若 API 报错，错误以红框/Snackbar 呈现（不崩溃）。
10. 选超过 10MB 的大图 → 提示「图片过大」。

- [ ] **Step 4: 记录结果**

把手测结果（通过/问题）回填到本计划或新开 issue；如全部通过，可在 `app/build.gradle.kts` 升版本号并按发布流程出包（交由维护者决定）。

---

## Self-Review（规划自检）

- **Spec 覆盖**：§3 模型→Task1；§4 处理→Task3；§5 适配器→Task1/2；§6 视觉识别→Task6(modelLikelySupportsVision+提示);§7 UI→Task6;§8 ViewModel→Task4/5;§9 权限→Task7;§10 错误 Snackbar→Task4/6;§11 测试→各 Task TDD + Task8 手测;§12 性能（下采样/不留 Base64）→Task3/5。主助手持久化属 Phase 2，未纳入（符合范围）。
- **占位符**：无 TBD/TODO；每个代码步给出完整代码。
- **类型一致**：`MessageContent.Image(base64,mimeType,width,height)`、`ImageAttachment(uri,info,thumbnail)`、`ImageInfo`、`ProcessedImage`、`computeTargetSize`/`encodeToBase64`、`addAttachment`/`removeAttachment`/`attachments`/`attachmentError`/`buildMultimodalMessage`、`openAiContentArray`/`claudeContentArray` 在各 Task 间签名一致。
- **风险点**：`ImageProcessor` 的 Bitmap/ContentResolver 与全部 Compose UI 无 JVM 单测，靠 Task8 真机手测；已在文首「测试边界」与各 Task 标注。
