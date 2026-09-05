package com.yumark.app.presentation.sync

import com.google.common.truth.Truth.assertThat
import com.yumark.app.R
import com.yumark.app.core.util.ErrorHandler
import com.yumark.app.core.util.FriendlyIOException
import com.yumark.app.core.util.UiMessage
import com.yumark.app.core.util.UserAction
import com.yumark.app.domain.model.SyncOutcome
import com.yumark.app.domain.model.WebDavConfig
import com.yumark.app.domain.repository.SyncRepository
import com.yumark.app.domain.usecase.sync.GetWebDavConfigUseCase
import com.yumark.app.domain.usecase.sync.ObserveLastSyncedAtUseCase
import com.yumark.app.domain.usecase.sync.SaveWebDavConfigUseCase
import com.yumark.app.domain.usecase.sync.SyncNowUseCase
import com.yumark.app.domain.usecase.sync.TestWebDavConnectionUseCase
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * [SyncSettingsViewModel] 的写盘路径。
 *
 * 这一组用例守的是一个**崩进程**级别的回归：`SyncConfigDataStore.updateConfig` 在密文那半边
 * 落盘失败时会抛 [FriendlyIOException]（主密钥被系统作废——改屏幕锁、恢复出厂、换机还原），
 * 而调用它的 `save()` / `sync()` 都在 `viewModelScope.launch` 里；从前那里没有 try/catch，
 * 于是「填完密码按返回键」就是一次未捕获异常。
 *
 * 断言方式刻意选了「message 等于某条具体文案」而不是「没有抛异常」：兜底缺失时协程在
 * `_state.value = …` 之前就死了，`message` 会停在 null，所以这条相等断言同时证明了
 * 「异常被接住」和「接住之后确实告诉了用户」——后者才是用户能感知的部分。
 *
 * 五个 use case 都只依赖同一个 [SyncRepository]（见 `SyncUseCases.kt`），所以一个 mockk 假仓库
 * 就能把整个 ViewModel 装起来，use case 本体用真的，不再多一层桩。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncSettingsViewModelTest {

    private lateinit var viewModel: SyncSettingsViewModel
    private val repository: SyncRepository = mockk()

    private val testDispatcher = StandardTestDispatcher()

    /** 已存好的配置：`isValid` 为真，`enabled` 为真，避免用例被前置校验挡住。 */
    private val storedConfig = WebDavConfig(
        enabled = true,
        baseUrl = "https://dav.example.com/dav/",
        username = "user",
        password = "secret",
        remoteDir = "YuMark"
    )

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // ErrorHandler 的 sink 是进程级单例，别的用例装过就会把这里的调用记进去
        ErrorHandler.reset()

        every { repository.observeConfig() } returns flowOf(storedConfig)
        every { repository.observeLastSyncedAt() } returns MutableStateFlow(null)
        coEvery { repository.saveConfig(any()) } returns Unit
        coEvery { repository.testConnection(any()) } returns Result.success(Unit)
        coEvery { repository.syncNow() } returns Result.success(SyncOutcome())

        viewModel = createViewModel()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    private fun createViewModel() = SyncSettingsViewModel(
        GetWebDavConfigUseCase(repository),
        ObserveLastSyncedAtUseCase(repository),
        SaveWebDavConfigUseCase(repository),
        TestWebDavConnectionUseCase(repository),
        SyncNowUseCase(repository)
    )

    /**
     * `ErrorHandler.report(带 UiMessage 的 FriendlyIOException, action)` 的组合结果。
     *
     * 形状来自 `ErrorHandler.compose`：「%1$s失败：%2$s」两段都是嵌套的 [UiMessage.Res]，
     * 拼接留到界面层。这里照抄这个形状而不是只断言「不为 null」，是为了同时钉住
     * 「动作标签是 SAVE_SETTINGS 而不是别的」这半边。
     */
    private fun expectedFailure(action: UserAction, reasonRes: Int): UiMessage = UiMessage.Res(
        R.string.error_action_failed,
        listOf(UiMessage.Res(action.labelRes), UiMessage.Res(reasonRes))
    )

    private val secretWriteFailed: FriendlyIOException
        get() = FriendlyIOException(UiMessage.of(R.string.secret_write_failed))

    @Test
    fun `save writes the edited config and reports success`() = runTest {
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.saveConfig(storedConfig) }
        assertThat(viewModel.state.value.message).isEqualTo(UiMessage.Res(R.string.saved))
    }

    @Test
    fun `save surfaces a secret-write failure instead of killing the process`() = runTest {
        // 只换了 suspend 桩，配置流没换，因此不必重建 ViewModel
        coEvery { repository.saveConfig(any()) } throws secretWriteFailed
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.state.value.message)
            .isEqualTo(expectedFailure(UserAction.SAVE_SETTINGS, R.string.secret_write_failed))
    }

    /**
     * 首帧之前不许写盘。第一次发射之前 `state.config` 是 `WebDavConfig()`（三个关键字段全空），
     * 而返回键是「顺手保存再退出」——闸门没了就是「进同步设置页随即退出」把存着的密码抹成空串，
     * 之后 `isValid` 为假，同步静静地停在「未配置」，用户却以为它还在后台跑。
     */
    @Test
    fun `save does nothing before the first config emission`() = runTest {
        every { repository.observeConfig() } returns MutableSharedFlow()
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.state.value.loaded).isFalse()
        coVerify(exactly = 0) { repository.saveConfig(any()) }
        // 什么都没做就不该弹提示，否则用户看到「已保存」而盘上没动
        assertThat(viewModel.state.value.message).isNull()
    }

    /**
     * 存不进去就别往下撞服务器。`syncNow()` 是自己去存储读配置的，拿到的会是**旧密码**，
     * 于是一条 401 盖住真正的原因；而部分 WebDAV 实现会把失败次数累计到账号锁定上。
     */
    @Test
    fun `sync aborts before touching the server when the pre-sync save fails`() = runTest {
        coEvery { repository.saveConfig(any()) } throws secretWriteFailed
        advanceUntilIdle()

        viewModel.sync()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.syncNow() }
        assertThat(viewModel.state.value.syncing).isFalse()
        assertThat(viewModel.state.value.message)
            .isEqualTo(expectedFailure(UserAction.SAVE_SETTINGS, R.string.secret_write_failed))
    }

    /** 保存成功才轮到同步，且顺序必须是「先存后传」——否则服务器拿到的是上一次的配置。 */
    @Test
    fun `sync saves first then runs and reports the counts`() = runTest {
        val outcome = SyncOutcome(uploaded = 2, downloaded = 1, deleted = 3)
        coEvery { repository.syncNow() } returns Result.success(outcome)
        advanceUntilIdle()

        viewModel.sync()
        advanceUntilIdle()

        coVerifyOrder {
            repository.saveConfig(storedConfig)
            repository.syncNow()
        }
        assertThat(viewModel.state.value.syncing).isFalse()
        assertThat(viewModel.state.value.lastOutcome).isEqualTo(outcome)
        assertThat(viewModel.state.value.message)
            .isEqualTo(UiMessage.Res(R.string.sync_result, listOf(2, 1, 3, 0, 0)))
    }

    /**
     * 有失败数走另一条键（六个占位符），而不是在 ViewModel 里拼「基础句 + 失败段」——
     * 拼接要先取到字符串，而这一层没有 Context。两条键各自的实参个数只能靠用例钉住：
     * 少一个实参在中文语区照样显示，到了英文语区才炸成 `IllegalFormatException`。
     */
    @Test
    fun `sync uses the failed-count wording when some documents failed`() = runTest {
        coEvery { repository.syncNow() } returns
            Result.success(SyncOutcome(uploaded = 1, failed = 2))
        advanceUntilIdle()

        viewModel.sync()
        advanceUntilIdle()

        assertThat(viewModel.state.value.message)
            .isEqualTo(UiMessage.Res(R.string.sync_result_with_failed, listOf(1, 0, 0, 0, 0, 2)))
    }

    /** 同 `save()`：首帧之前 `sync()` 的第一件事就是写盘，写进去的是空配置。 */
    @Test
    fun `sync does nothing before the first config emission`() = runTest {
        every { repository.observeConfig() } returns MutableSharedFlow()
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.sync()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.saveConfig(any()) }
        coVerify(exactly = 0) { repository.syncNow() }
        assertThat(viewModel.state.value.syncing).isFalse()
    }

    @Test
    fun `test connection reports success without writing anything`() = runTest {
        advanceUntilIdle()

        viewModel.test()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.testConnection(storedConfig) }
        // 「测试连接」不落盘：它是拿当前输入去撞一次服务器，用户还没决定要不要留下这套配置
        coVerify(exactly = 0) { repository.saveConfig(any()) }
        assertThat(viewModel.state.value.testing).isFalse()
        assertThat(viewModel.state.value.message)
            .isEqualTo(UiMessage.Res(R.string.sync_test_success))
    }

    /**
     * 失败文案走 [ErrorHandler]，动作标签是 [UserAction.CONNECT]。
     * 这条同时守着一件安全的事：透出的是 `WebDavClient` 给的那句话，而不是异常原文——
     * 后者带着完整请求地址，而 WebDAV 的 URL 里可能内嵌账号密码。
     */
    @Test
    fun `test connection failure surfaces the mapped reason`() = runTest {
        coEvery { repository.testConnection(any()) } returns
            Result.failure(FriendlyIOException(UiMessage.of(R.string.webdav_error_unauthorized)))
        advanceUntilIdle()

        viewModel.test()
        advanceUntilIdle()

        assertThat(viewModel.state.value.testing).isFalse()
        assertThat(viewModel.state.value.message)
            .isEqualTo(expectedFailure(UserAction.CONNECT, R.string.webdav_error_unauthorized))
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
}
