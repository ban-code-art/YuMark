package com.yumark.app.presentation.ai.config

import com.google.common.truth.Truth.assertThat
import com.yumark.app.R
import com.yumark.app.core.util.ErrorHandler
import com.yumark.app.core.util.FriendlyIOException
import com.yumark.app.core.util.UiMessage
import com.yumark.app.core.util.UserAction
import com.yumark.app.data.ai.AiAdapterFactory
import com.yumark.app.data.ai.AiApiAdapter
import com.yumark.app.domain.model.AiConfig
import com.yumark.app.domain.model.AiProvider
import com.yumark.app.domain.model.ModelInfo
import com.yumark.app.domain.repository.AiConfigRepository
import com.yumark.app.domain.usecase.ai.FetchAvailableModelsUseCase
import com.yumark.app.domain.usecase.ai.FetchRagModelsUseCase
import com.yumark.app.domain.usecase.ai.GetAiConfigUseCase
import com.yumark.app.domain.usecase.ai.TestAiConnectionUseCase
import com.yumark.app.domain.usecase.ai.UpdateAiConfigUseCase
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [AiConfigViewModel] 的写盘路径，与 `SyncSettingsViewModelTest` 对称。
 *
 * 两个加密存储（`AiConfigDataStore` / `SyncConfigDataStore`）的缺陷是同一个：密文那半边写不进去
 * 时 `updateConfig` 抛 [FriendlyIOException]（主密钥被系统作废——改屏幕锁、恢复出厂、换机还原），
 * 而 `save()` 跑在 `viewModelScope.launch` 里，从前没有 try/catch，于是「填完 API Key 按返回键」
 * 就是一次未捕获异常，直接崩进程。
 *
 * 断言的是 `message` 等于某条具体文案而不是「没抛异常」：兜底缺失时协程在
 * `_state.value = …` 之前就死了，`message` 会停在 null，所以相等断言同时钉住
 * 「异常被接住」与「接住之后确实报给了用户」。
 *
 * 四个 use case 只依赖 [AiConfigRepository] 与 [AiAdapterFactory] 两个协作者，所以两个 mockk
 * 就能把 ViewModel 装起来；use case 本体用真的。`testConnection` / `fetchModels` 这两条路
 * 本组用例不走，工厂因此不需要任何桩。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiConfigViewModelTest {

    private lateinit var viewModel: AiConfigViewModel
    private val repository: AiConfigRepository = mockk()
    private val adapterFactory: AiAdapterFactory = mockk()

    private val testDispatcher = StandardTestDispatcher()

    /** 已存好的配置：apiKey 非空，正是「不能被空配置覆盖」的那一份。 */
    private val storedConfig = AiConfig(
        enabled = true,
        provider = AiProvider.OPENAI,
        apiKey = "sk-stored",
        baseUrl = "https://api.example.com/v1/",
        modelName = "gpt-4o-mini"
    )

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // ErrorHandler 的 sink 是进程级单例，别的用例装过就会把这里的调用记进去
        ErrorHandler.reset()

        every { repository.observeConfig() } returns flowOf(storedConfig)
        coEvery { repository.updateConfig(any()) } returns Unit

        viewModel = createViewModel()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    private fun createViewModel() = AiConfigViewModel(
        GetAiConfigUseCase(repository),
        UpdateAiConfigUseCase(repository),
        TestAiConnectionUseCase(adapterFactory),
        FetchAvailableModelsUseCase(adapterFactory, repository),
        FetchRagModelsUseCase(adapterFactory, repository)
    )

    /** 形状来自 `ErrorHandler.compose`：两段都是嵌套的 [UiMessage.Res]，拼接留到界面层。 */
    private fun expectedFailure(action: UserAction, reasonRes: Int): UiMessage = UiMessage.Res(
        R.string.error_action_failed,
        listOf(UiMessage.Res(action.labelRes), UiMessage.Res(reasonRes))
    )

    @Test
    fun `save writes the edited config and reports success`() = runTest {
        advanceUntilIdle()
        viewModel.onModelChange("gpt-4o")
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateConfig(storedConfig.copy(modelName = "gpt-4o")) }
        assertThat(viewModel.state.value.message).isEqualTo(UiMessage.Res(R.string.saved))
    }

    @Test
    fun `save surfaces a secret-write failure instead of killing the process`() = runTest {
        // 只换了 suspend 桩，配置流没换，因此不必重建 ViewModel
        coEvery { repository.updateConfig(any()) } throws
            FriendlyIOException(UiMessage.of(R.string.secret_write_failed))
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.state.value.message)
            .isEqualTo(expectedFailure(UserAction.SAVE_SETTINGS, R.string.secret_write_failed))
    }

    /**
     * 首帧之前不许写盘。第一次发射之前 `state.config` 是 `AiConfig()`（apiKey 空串、enabled 假），
     * 而返回键是「顺手保存再退出」——闸门没了就是「进配置页随即退出」把用户存着的 API Key
     * 覆盖成空串，下次进来看到「AI 未启用」+ 空密钥，且没有任何提示说明刚发生了什么。
     */
    @Test
    fun `save does nothing before the first config emission`() = runTest {
        every { repository.observeConfig() } returns MutableSharedFlow()
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.state.value.loaded).isFalse()
        coVerify(exactly = 0) { repository.updateConfig(any()) }
        // 走到这条路径的用户压根没在保存（他在退出），弹提示只是噪声
        assertThat(viewModel.state.value.message).isNull()
    }

    /** 首帧之后的编辑不该被后续发射覆盖：`loaded` 之后以本地输入为准。 */
    @Test
    fun `later emissions do not clobber local edits`() = runTest {
        val upstream = MutableSharedFlow<AiConfig>(replay = 1)
        upstream.emit(storedConfig)
        every { repository.observeConfig() } returns upstream
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onApiKeyChange("sk-typed-by-user")
        upstream.emit(storedConfig)
        advanceUntilIdle()

        assertThat(viewModel.state.value.config.apiKey).isEqualTo("sk-typed-by-user")
    }

    @Test
    fun `consumeMessage clears the snackbar so it does not replay`() = runTest {
        advanceUntilIdle()
        viewModel.save()
        advanceUntilIdle()
        assertThat(viewModel.state.value.message).isNotNull()

        viewModel.consumeMessage()

        assertThat(viewModel.state.value.message).isNull()
    }

    /**
     * 「单独配置」选了、地址没填就按「获取模型」：用例在发请求之前拦下来，
     * 界面收到的是「请先填写 Base URL」而不是一条 Ktor 相对 URL 异常。
     * factory 因此整段没有被调用——地址为空时根本轮不到它出场。
     */
    @Test
    fun `fetchRagModelList rejects a blank separate base url before any request`() = runTest {
        advanceUntilIdle()
        viewModel.onRagUseMainEndpointChange(false)
        // ragBaseUrl 留空：这正是要测的状态
        advanceUntilIdle()

        viewModel.fetchRagModelList()
        advanceUntilIdle()

        coVerify(exactly = 0) { adapterFactory.createRagModelListAdapter(any()) }
        assertThat(viewModel.state.value.isFetchingRagModels).isFalse()
        assertThat(viewModel.state.value.message)
            .isEqualTo(expectedFailure(UserAction.FETCH_MODELS, R.string.ai_config_rag_base_url_required))
    }

    @Test
    fun `fetchRagModelList stores the fetched list into ragAvailableModels`() = runTest {
        advanceUntilIdle()
        viewModel.onRagUseMainEndpointChange(false)
        viewModel.onRagBaseUrlChange("https://embed.example.com/v1")
        advanceUntilIdle()

        val listAdapter = mockk<AiApiAdapter>()
        coEvery { adapterFactory.createRagModelListAdapter(any()) } returns listAdapter
        coEvery { listAdapter.fetchAvailableModels() } returns listOf(
            ModelInfo(id = "text-embedding-3-small", name = "Embedding 3 Small")
        )

        viewModel.fetchRagModelList()
        advanceUntilIdle()

        assertThat(viewModel.state.value.config.ragAvailableModels)
            .containsExactly("text-embedding-3-small")
        assertThat(viewModel.state.value.isFetchingRagModels).isFalse()
        assertThat(viewModel.state.value.message)
            .isEqualTo(UiMessage.Res(R.string.ai_config_models_fetched, listOf(1)))
    }
}
