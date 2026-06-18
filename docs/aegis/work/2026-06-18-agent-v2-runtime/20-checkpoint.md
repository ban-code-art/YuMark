# Agent V2 Runtime Checkpoint

## TodoCheckpointDraft

Current todo:

- Final verification / packaging remains after this checkpoint if a release build or commit is requested.

Completed todos:

- design spec written
- implementation plan written
- current dirty workspace approved by user for inline execution
- Task 1: introduced Agent task state persistence
- Task 2: added planner and completion parser contracts
- Task 3: added executor, single-replan runtime contracts, and initial task creation in `SendAgentMessageUseCase`
- Task 4: exposed persisted task progress through `AgentChatViewModel.taskProgress`
- Task 4: rendered compact task-progress panel in `AgentContent`
- Runtime follow-up: `SendAgentMessageUseCase` now performs a bounded planner request before the answer loop
- Runtime follow-up: planner JSON success creates persisted multi-step task state; malformed planner output falls back to the legacy single-step plan
- Runtime follow-up: persisted task steps now advance through RUNNING/DONE during non-write tool execution
- Runtime follow-up: write tool proposals now share the persisted step RUNNING/DONE path while still avoiding direct write execution
- Runtime follow-up: `SendAgentMessageUseCase` now performs a bounded completion-judge request after normal answer and write-proposal exits.
- Runtime follow-up: completion-judge decisions now finalize persisted task state as `COMPLETED`, `BLOCKED`, or `FAILED` with `finalSummary` / `blockingReason`.
- Runtime follow-up: completion-judge input now receives the latest in-memory step status/result snapshot instead of the initial pending plan.
- Runtime follow-up: repeated-tool and max-step runtime guards now finalize persisted task state directly as blocked/failed.
- Feedback fix: underspecified document creation prompts now ask only for missing save/create location before using `create_document`; title, structure, and depth are inferred by the agent unless the user voluntarily specifies them.
- Feedback fix: completed task progress no longer persists as a top-of-chat panel, and non-running terminal panels do not show stale pending steps.
- Feedback fix: document creation clarification now accumulates optional follow-up preferences across turns and resumes the original creation request once save/create location is present.

Active slice:

- Completion evaluation and blocked/failed task finalization in the send loop completed.

Evidence refs:

- RED: `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.data.repository.AgentTaskRepositoryImplTest` failed on deterministic aggregate ordering before mapper sorting.
- GREEN: `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.data.repository.AgentTaskRepositoryImplTest` produced `BUILD SUCCESSFUL`.
- Regression: `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest` produced `BUILD SUCCESSFUL`.
- Room schema export now includes `app/schemas/com.yumark.app.data.local.db.AppDatabase/6.json`.
- RED: `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.domain.usecase.ai.agent.PlanAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.EvaluateTaskCompletionUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.ParseWriteToolCallTest` failed because `PlanAgentTaskUseCase`, `EvaluateTaskCompletionUseCase`, and `TaskCompletionOutcome` did not exist.
- GREEN: the same Task 2 command produced `BUILD SUCCESSFUL` after adding parser contracts.
- RED: `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.domain.usecase.ai.agent.ExecuteAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.ReplanAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest` failed because executor/replanner classes did not exist.
- RED: `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest` failed when the test expected task runtime dependencies and task creation before production wiring.
- GREEN: `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.domain.usecase.ai.agent.ExecuteAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.ReplanAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest` produced `BUILD SUCCESSFUL`.
- RED: `AgentChatViewModelTest` was updated to require exposed task progress for executing, blocked, and completed task states before `TaskProgressUiState` existed.
- GREEN: `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.presentation.ai.agent.AgentChatViewModelTest` produced `BUILD SUCCESSFUL`.
- Regression: `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.presentation.ai.agent.AgentChatViewModelTest --tests com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest` produced `BUILD SUCCESSFUL`.
- Agent V2 targeted suite: `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.data.repository.AgentTaskRepositoryImplTest --tests com.yumark.app.domain.usecase.ai.agent.PlanAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.EvaluateTaskCompletionUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.ExecuteAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.ReplanAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest --tests com.yumark.app.presentation.ai.agent.AgentChatViewModelTest` produced `BUILD SUCCESSFUL`.
- RED: `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.creates*` failed because `SendAgentMessageUseCase` did not request a planner JSON round before the answer loop.
- GREEN: the same planner-wiring test produced `BUILD SUCCESSFUL` after `SendAgentMessageUseCase` called `requestInitialPlan`.
- Regression: `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest` produced `BUILD SUCCESSFUL`.
- Agent V2 targeted suite after planner wiring: `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.data.repository.AgentTaskRepositoryImplTest --tests com.yumark.app.domain.usecase.ai.agent.PlanAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.EvaluateTaskCompletionUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.ExecuteAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.ReplanAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest --tests com.yumark.app.presentation.ai.agent.AgentChatViewModelTest` produced `BUILD SUCCESSFUL`.
- RED: `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.updates*` failed because persisted task step status did not advance during tool execution.
- GREEN: the same step-status test produced `BUILD SUCCESSFUL` after `SendAgentMessageUseCase` marked the active planned step `RUNNING` and `DONE`.
- Regression: `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest` produced `BUILD SUCCESSFUL`.
- Agent V2 targeted suite after step-status wiring: `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.data.repository.AgentTaskRepositoryImplTest --tests com.yumark.app.domain.usecase.ai.agent.PlanAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.EvaluateTaskCompletionUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.ExecuteAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.ReplanAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest --tests com.yumark.app.presentation.ai.agent.AgentChatViewModelTest` produced `BUILD SUCCESSFUL`.
- RED: `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.write*` failed because write tool proposals still bypassed persisted step status updates.
- GREEN: `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.write* --tests com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.edit_document*` produced `BUILD SUCCESSFUL` after write proposals moved into the shared step path.
- Regression: `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest` produced `BUILD SUCCESSFUL`.
- Agent V2 targeted suite after write proposal wiring: `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.data.repository.AgentTaskRepositoryImplTest --tests com.yumark.app.domain.usecase.ai.agent.PlanAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.EvaluateTaskCompletionUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.ExecuteAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.ReplanAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest --tests com.yumark.app.presentation.ai.agent.AgentChatViewModelTest` produced `BUILD SUCCESSFUL`.
- RED: `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.finalizes*"` failed because the send loop never made a completion-judge request and the persisted task was not finalized.
- GREEN: the same finalization test command produced `BUILD SUCCESSFUL` after adding completion-judge finalization to `SendAgentMessageUseCase`.
- RED: `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.completion*"` failed because the completion judge received the initial pending step plan instead of the latest DONE/result state.
- GREEN: the same completion input test command produced `BUILD SUCCESSFUL` after maintaining an in-memory persisted-step snapshot for the judge request.
- Regression: `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest"` produced `BUILD SUCCESSFUL`.
- Agent V2 aggregate suite: `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.PlanAgentTaskUseCaseTest" --tests "com.yumark.app.domain.usecase.ai.agent.ExecuteAgentTaskUseCaseTest" --tests "com.yumark.app.domain.usecase.ai.agent.ReplanAgentTaskUseCaseTest" --tests "com.yumark.app.domain.usecase.ai.agent.EvaluateTaskCompletionUseCaseTest" --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest" --tests "com.yumark.app.presentation.ai.agent.AgentChatViewModelTest" --tests "com.yumark.app.data.ai.adapters.ProviderToolMessageFormattingTest"` produced `BUILD SUCCESSFUL`.
- Full debug unit suite: `cmd /c gradlew.bat :app:testDebugUnitTest` produced `BUILD SUCCESSFUL`.
- Compile check: `cmd /c gradlew.bat :app:compileDebugKotlin` produced `BUILD SUCCESSFUL`.
- RED: `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.*prompt*" --tests "com.yumark.app.presentation.ai.agent.AgentChatViewModelTest.completed*"` failed because creation prompts did not require clarification and completed task progress remained visible.
- GREEN: the same prompt/UI focused command produced `BUILD SUCCESSFUL` after prompt tightening and completed-progress hiding.
- Regression: `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest" --tests "com.yumark.app.presentation.ai.agent.AgentChatViewModelTest"` produced `BUILD SUCCESSFUL`.
- Regression: `cmd /c gradlew.bat :app:testDebugUnitTest` produced `BUILD SUCCESSFUL`.
- RED: `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.document creation clarification follow up*"` failed because follow-up answers after a clarification were treated as standalone chat instead of resuming the original creation request.
- GREEN: the same follow-up command produced `BUILD SUCCESSFUL` after adding clarification-context recovery.
- RED: `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.document creation clarification accumulates*"` failed because only the latest follow-up answer was considered, losing earlier supplied title/structure details.
- GREEN: `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.document creation clarification*"` produced `BUILD SUCCESSFUL` after accumulating all user answers in the pending clarification segment.
- Regression: `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest" --tests "com.yumark.app.presentation.ai.agent.AgentChatViewModelTest"` produced `BUILD SUCCESSFUL`.
- Regression: `cmd /c gradlew.bat :app:testDebugUnitTest` produced `BUILD SUCCESSFUL`.
- RED: `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.*creation*"` failed after tightening the tests because the clarification gate still treated title/structure/depth as required user-supplied slots.
- GREEN: the same creation-focused command produced `BUILD SUCCESSFUL` after changing the hard clarification gate to only require save/create location and let the agent infer title/structure/depth.
- Regression: `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest" --tests "com.yumark.app.presentation.ai.agent.AgentChatViewModelTest"` produced `BUILD SUCCESSFUL`.
- Regression: `cmd /c gradlew.bat :app:testDebugUnitTest` produced `BUILD SUCCESSFUL`.

Blocked-on items:

- none

Next step:

- If continuing this branch, perform packaging/release validation, manual device UX verification, or create a commit/PR boundary.

## ResumeStateHint

Resume by reading:

- `docs/aegis/plans/2026-06-18-agent-v2-runtime-implementation-plan.md`
- this checkpoint
- latest `git status --short`
- Task 1 files: `AgentTaskModels.kt`, `AgentTaskRepository.kt`, `AgentTaskEntities.kt`, `AgentTaskDao.kt`, `AgentTaskMappers.kt`, `AgentTaskRepositoryImpl.kt`, `AppDatabase.kt`, `DatabaseModule.kt`, `RepositoryModule.kt`, `AgentTaskRepositoryImplTest.kt`
- Task 2 files: `AgentRuntimeJson.kt`, `PlanAgentTaskUseCase.kt`, `EvaluateTaskCompletionUseCase.kt`, `PlanAgentTaskUseCaseTest.kt`, `EvaluateTaskCompletionUseCaseTest.kt`
- Task 3 files: `ExecuteAgentTaskUseCase.kt`, `ReplanAgentTaskUseCase.kt`, `ExecuteAgentTaskUseCaseTest.kt`, `ReplanAgentTaskUseCaseTest.kt`, `SendAgentMessageUseCaseTest.kt`, `AgentUseCases.kt`
- Task 4 files: `AgentChatSheet.kt`, `AgentChatViewModelTest.kt`
- Planner wiring files: `AgentUseCases.kt`, `SendAgentMessageUseCaseTest.kt`
- Step-status wiring files: `AgentUseCases.kt`, `SendAgentMessageUseCaseTest.kt`
- Write-proposal wiring files: `AgentUseCases.kt`, `SendAgentMessageUseCaseTest.kt`
- Completion finalization files: `AgentUseCases.kt`, `SendAgentMessageUseCaseTest.kt`, `docs/aegis/work/2026-06-18-agent-v2-runtime/90-evidence.md`
- Multi-turn clarification files: `AgentUseCases.kt`, `SendAgentMessageUseCaseTest.kt`, `docs/aegis/work/2026-06-18-agent-v2-runtime/90-evidence.md`

The current workspace is intentionally dirty and user-approved for inline execution.

## DriftCheckDraft

Decision: continue

- still serves original task intent
- compatibility boundary unchanged
- no new fallback path introduced
- task persistence source of truth added without changing provider adapter contracts or write approval behavior
- planner/completion contracts added without changing provider adapter contracts, write approval behavior, or `SendAgentMessageUseCase`
- executor/replanner contracts added without changing provider adapter contracts or write approval behavior
- `SendAgentMessageUseCase` creates an initial task but still retains the legacy streaming loop; full UI progress exposure remains Task 4
- Task progress is now visible from persisted task state while transient streaming narration remains as supporting detail
- planner output is now part of the real send path, but persisted task step statuses do not yet advance with each model/tool loop iteration
- persisted task steps now advance for non-write tool calls and write proposals
- task finalization now uses `EvaluateTaskCompletionUseCase` for normal answer/write-proposal exits and direct guard decisions for repeated-tool/max-step exits
- completion finalization preserves provider adapter contracts and write approval behavior
- full debug unit evidence is sufficient for this branch-level unit-test claim, but not for claiming full Claude Code/Codex parity or device/manual UX parity
