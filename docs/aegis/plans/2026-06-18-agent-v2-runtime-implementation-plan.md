# Agent V2 Runtime Implementation Plan

Goal: implement the approved Agent V2 runtime in bounded slices so YuMark's document agent can plan, execute, replan once, and judge completion without regressing existing chat and write-approval behavior.

Architecture: extend the current Clean Architecture flow by introducing task-state persistence and orchestration use cases around the existing adapter and document-tool layers, while preserving current provider contracts and chat mode boundaries.

Tech Stack:

- Kotlin
- Android + Compose
- Hilt
- Room
- Ktor AI adapters
- JUnit 5 + MockK + Truth + Turbine

Baseline/Authority Refs:

- `docs/aegis/specs/2026-06-18-agent-v2-runtime-design.md`
- `docs/aegis/baseline/2026-06-18-initial-baseline.md`
- `docs/ARCHITECTURE.md`
- `docs/agent-gap-analysis-2026-06-17.md`
- `app/src/main/java/com/yumark/app/domain/usecase/ai/agent/AgentUseCases.kt`
- `app/src/main/java/com/yumark/app/presentation/ai/agent/AgentChatSheet.kt`
- `app/src/main/java/com/yumark/app/data/local/db/entity/AiEntities.kt`

Compatibility Boundary:

- do not break standard chat mode
- keep `AiApiAdapter.sendChatStream(messages, config, tools)` stable
- keep document create/save/load use cases stable
- keep explicit user approval as the only path to write execution
- preserve current conversation/message rendering outside the new task-progress additions

Verification:

- targeted unit tests for new task repository, planner parser, executor, and view model wiring
- regression tests for existing `SendAgentMessageUseCase` behavior that remains supported
- focused Gradle test commands per slice and one aggregate agent/adapters suite

## Scope Check

Facts:

- current agent orchestration is concentrated in one use case
- conversation/message persistence already exists in Room
- provider adapters now support tool-calling transport well enough to build on
- write approval remains a hard requirement

Assumptions:

- V1 task persistence can be implemented in Room without introducing background workers
- planner, executor, replanner, and completion judge can all be introduced without changing adapter interfaces
- simple requests may still use a lighter path when that meaningfully reduces latency

Unknowns:

- exact persistence shape for checkpoints: dedicated table vs JSON field
- whether first planner prompt should be universal or only for tasks above a complexity threshold
- whether current streamed assistant-message behavior should remain the main transcript mechanism for in-flight steps

Ripple Signal Triage:

- Owner scope expands: domain orchestration, Room schema, repositories, DI, and agent UI all change together
- Downstream scope expands: conversation persistence mapping and agent rendering must remain coherent
- Contract scope expands: new task repository contract and new domain models are introduced
- Source-of-truth scope expands: task state becomes a new source of truth alongside conversation/message rows
- Verification scope expands: agent runtime now needs state-machine tests, not just parser and adapter tests

Dual-track needs:

- Repair track: move control logic out of the monolithic `SendAgentMessageUseCase`
- Retirement track: gradually demote old free-form planning logic and textual fallback assumptions rather than deleting everything in one slice

## File Map

Create:

- `app/src/main/java/com/yumark/app/domain/model/AgentTaskModels.kt`
- `app/src/main/java/com/yumark/app/domain/repository/AgentTaskRepository.kt`
- `app/src/main/java/com/yumark/app/domain/usecase/ai/agent/PlanAgentTaskUseCase.kt`
- `app/src/main/java/com/yumark/app/domain/usecase/ai/agent/ExecuteAgentTaskUseCase.kt`
- `app/src/main/java/com/yumark/app/domain/usecase/ai/agent/ReplanAgentTaskUseCase.kt`
- `app/src/main/java/com/yumark/app/domain/usecase/ai/agent/EvaluateTaskCompletionUseCase.kt`
- `app/src/main/java/com/yumark/app/domain/usecase/ai/agent/SummarizeTaskProgressUseCase.kt`
- `app/src/main/java/com/yumark/app/data/local/db/entity/AgentTaskEntities.kt`
- `app/src/main/java/com/yumark/app/data/local/db/dao/AgentTaskDao.kt`
- `app/src/main/java/com/yumark/app/data/repository/AgentTaskRepositoryImpl.kt`
- `app/src/test/java/com/yumark/app/domain/usecase/ai/agent/PlanAgentTaskUseCaseTest.kt`
- `app/src/test/java/com/yumark/app/domain/usecase/ai/agent/ExecuteAgentTaskUseCaseTest.kt`
- `app/src/test/java/com/yumark/app/domain/usecase/ai/agent/ReplanAgentTaskUseCaseTest.kt`
- `app/src/test/java/com/yumark/app/domain/usecase/ai/agent/EvaluateTaskCompletionUseCaseTest.kt`

Modify:

- `app/src/main/java/com/yumark/app/domain/usecase/ai/agent/AgentUseCases.kt`
- `app/src/main/java/com/yumark/app/presentation/ai/agent/AgentChatSheet.kt`
- `app/src/main/java/com/yumark/app/data/local/db/AppDatabase.kt`
- `app/src/main/java/com/yumark/app/data/local/db/entity/AiEntities.kt` only if task summary metadata must live there
- `app/src/main/java/com/yumark/app/data/local/db/dao/AiDao.kt` if task state integrates with existing AI DAOs
- `app/src/main/java/com/yumark/app/data/mapper/AiMappers.kt`
- `app/src/main/java/com/yumark/app/data/repository/ConversationRepositoryImpl.kt` only if task summary touches conversation materialization
- `app/src/main/java/com/yumark/app/di/DatabaseModule.kt`
- `app/src/main/java/com/yumark/app/di/RepositoryModule.kt`
- `app/src/test/java/com/yumark/app/domain/usecase/ai/agent/SendAgentMessageUseCaseTest.kt`
- `app/src/test/java/com/yumark/app/presentation/ai/agent/AgentChatViewModelTest.kt`

## Plan Structure

Implement in four slices:

1. task-state persistence and domain model foundation
2. planner and completion parser layer
3. executor orchestration and bounded replanning
4. UI progress state and end-to-end agent entry rewiring

## Task 1: Introduce Agent Task State Persistence

Files:

- Create:
  - `app/src/main/java/com/yumark/app/domain/model/AgentTaskModels.kt`
  - `app/src/main/java/com/yumark/app/domain/repository/AgentTaskRepository.kt`
  - `app/src/main/java/com/yumark/app/data/local/db/entity/AgentTaskEntities.kt`
  - `app/src/main/java/com/yumark/app/data/local/db/dao/AgentTaskDao.kt`
  - `app/src/main/java/com/yumark/app/data/repository/AgentTaskRepositoryImpl.kt`
- Modify:
  - `app/src/main/java/com/yumark/app/data/local/db/AppDatabase.kt`
  - `app/src/main/java/com/yumark/app/data/mapper/AiMappers.kt`
  - `app/src/main/java/com/yumark/app/di/DatabaseModule.kt`
  - `app/src/main/java/com/yumark/app/di/RepositoryModule.kt`

Why:

- the runtime cannot plan or replan safely without a dedicated task source of truth

Impact/Compatibility:

- new Room entities and migration required
- no change to existing provider adapter contracts
- no write-path behavior change yet

Repair Track:

- root cause: agent state currently lives only in stream-local variables and message rows
- canonical owner after repair: `AgentTaskRepository`

Retirement Track:

- old owner retained temporarily: `Message.steps` remains for user-visible narration
- deletion trigger: after task UI fully owns progress rendering and no feature depends on transient-only step lists

Verification:

- `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.domain.usecase.ai.agent.AgentTaskRepositoryTest`
- `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest`

Steps:

- [ ] Write the failing repository and mapper tests for create/get/update/replace/append operations.
- [ ] Run the targeted repository test command and confirm RED.
- [ ] Implement the minimal domain models, Room entities/DAO, mapper functions, repository implementation, DI bindings, and database migration to satisfy the tests.
- [ ] Run the targeted repository test command and confirm GREEN.
- [ ] Commit the slice.

## Task 2: Add Planner and Completion Data Contracts

Files:

- Create:
  - `app/src/main/java/com/yumark/app/domain/usecase/ai/agent/PlanAgentTaskUseCase.kt`
  - `app/src/main/java/com/yumark/app/domain/usecase/ai/agent/EvaluateTaskCompletionUseCase.kt`
  - `app/src/test/java/com/yumark/app/domain/usecase/ai/agent/PlanAgentTaskUseCaseTest.kt`
  - `app/src/test/java/com/yumark/app/domain/usecase/ai/agent/EvaluateTaskCompletionUseCaseTest.kt`
- Modify:
  - `app/src/main/java/com/yumark/app/domain/usecase/ai/agent/AgentUseCases.kt` only for extracted parser helpers if needed

Why:

- planning and completion need deterministic parsing boundaries before runtime rewiring starts

Impact/Compatibility:

- parser-only slice, no user-visible orchestration change yet
- planner must tolerate malformed model output safely

Repair Track:

- root cause: current completion is tied to stream stop and free-form action inference
- canonical owner after repair: dedicated planner/completion use cases

Retirement Track:

- old fallback retained temporarily: legacy `parseAgentAction` remains available for compatibility until executor slice lands
- deletion trigger: once task runtime fully routes write proposals through task-step completion and explicit action evidence

Verification:

- `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.domain.usecase.ai.agent.PlanAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.EvaluateTaskCompletionUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.ParseWriteToolCallTest`

Steps:

- [ ] Write failing tests for planner JSON parsing, invalid-tool rejection, missing-field handling, and completion-judge outcome selection.
- [ ] Run the targeted planner/completion test command and confirm RED.
- [ ] Implement the minimal planner result model, parser, validation, and completion-evaluation rules to satisfy the tests.
- [ ] Run the targeted planner/completion test command and confirm GREEN.
- [ ] Commit the slice.

## Task 3: Add Executor and Single-Replan Runtime Slice

Files:

- Create:
  - `app/src/main/java/com/yumark/app/domain/usecase/ai/agent/ExecuteAgentTaskUseCase.kt`
  - `app/src/main/java/com/yumark/app/domain/usecase/ai/agent/ReplanAgentTaskUseCase.kt`
  - `app/src/main/java/com/yumark/app/domain/usecase/ai/agent/SummarizeTaskProgressUseCase.kt`
  - `app/src/test/java/com/yumark/app/domain/usecase/ai/agent/ExecuteAgentTaskUseCaseTest.kt`
  - `app/src/test/java/com/yumark/app/domain/usecase/ai/agent/ReplanAgentTaskUseCaseTest.kt`
- Modify:
  - `app/src/main/java/com/yumark/app/domain/usecase/ai/agent/AgentUseCases.kt`
  - `app/src/test/java/com/yumark/app/domain/usecase/ai/agent/SendAgentMessageUseCaseTest.kt`

Why:

- this is the slice that turns the planner artifacts into actual bounded runtime behavior

Impact/Compatibility:

- `SendAgentMessageUseCase` changes from monolithic executor to coordinator
- existing tool calling, tool execution, and write approval must remain intact
- bounded single replan only in V1

Repair Track:

- root cause: orchestration, execution, and termination logic are all embedded in one loop
- canonical owner after repair: coordinator + executor/replanner use cases

Retirement Track:

- old owner kept in reduced form: legacy in-use-case loop may survive briefly as simple-path fallback
- deletion trigger: once simple-path routing is explicitly implemented and covered by tests

Verification:

- `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.domain.usecase.ai.agent.ExecuteAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.ReplanAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest`

Steps:

- [ ] Write failing tests for step progression, evidence capture, blocked-state output, one-shot replanning, and write-proposal preservation.
- [ ] Run the targeted executor/replanner test command and confirm RED.
- [ ] Implement the minimal executor, replanner, progress summarizer, and `SendAgentMessageUseCase` orchestration rewrite needed to satisfy the tests.
- [ ] Run the targeted executor/replanner test command and confirm GREEN.
- [ ] Commit the slice.

## Task 4: Expose Task Progress in Agent UI

Files:

- Modify:
  - `app/src/main/java/com/yumark/app/presentation/ai/agent/AgentChatSheet.kt`
  - `app/src/main/java/com/yumark/app/domain/usecase/ai/agent/AgentUseCases.kt`
  - `app/src/test/java/com/yumark/app/presentation/ai/agent/AgentChatViewModelTest.kt`

Why:

- a smarter runtime must be visible to the user as task progress, not just hidden orchestration

Impact/Compatibility:

- chat transcript remains intact
- new UI should show goal, steps, current state, replanning, and blocking reasons without overwhelming the user

Repair Track:

- root cause: existing UI only exposes tool narration and streaming text
- canonical owner after repair: task-progress UI state in `AgentChatViewModel`

Retirement Track:

- old owner retained: transient `steps` list can remain as supporting narration until task panel fully replaces it
- deletion trigger: once no UI branch depends on the old narration-only model

Verification:

- `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.presentation.ai.agent.AgentChatViewModelTest`
- `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest --tests com.yumark.app.presentation.ai.agent.AgentChatViewModelTest`

Steps:

- [ ] Write failing view-model tests for task goal exposure, active-step updates, blocked-state exposure, and completion summary handling.
- [ ] Run the targeted view-model test command and confirm RED.
- [ ] Implement the minimal task-progress UI state and Compose rendering changes to satisfy the tests.
- [ ] Run the targeted view-model test command and confirm GREEN.
- [ ] Commit the slice.

## Aggregate Verification

Run after all slices:

- `./gradlew.bat :app:testDebugUnitTest --tests com.yumark.app.domain.usecase.ai.agent.PlanAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.ExecuteAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.ReplanAgentTaskUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.EvaluateTaskCompletionUseCaseTest --tests com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest --tests com.yumark.app.presentation.ai.agent.AgentChatViewModelTest --tests com.yumark.app.data.ai.adapters.ProviderToolMessageFormattingTest`

Expected output:

- `BUILD SUCCESSFUL`

## Risks and Rollback Surface

- Room migration errors can break AI conversation persistence if schema changes are careless.
- `SendAgentMessageUseCase` is a high-churn file and can accidentally regress current proposal or streaming behavior.
- Planner prompt/protocol fragility may require a tighter parser than initially expected.
- UI regressions are likely if task progress and old streaming steps compete for rendering ownership.

Rollback strategy:

- each slice is commit-bounded and can be reverted independently
- task repository introduction should be isolated from orchestration rewrite
- legacy parsing and simple-path behavior should remain until replacement is verified green

## Spec Coverage Check

- structured task planning: Task 2
- explicit step state tracking: Tasks 1 and 3
- evidence capture: Tasks 1 and 3
- bounded replanning: Task 3
- completion evaluation: Task 2
- UI-visible task progress: Task 4
- compatibility boundary preservation: all tasks

## Self-Review

- Placeholder scan: no unresolved placeholders in task instructions
- Type consistency: task repository precedes orchestration changes; parser layer precedes runtime control
- Compatibility: stable adapter interface and write approval boundary are called out in every relevant slice
- Verification: every slice has exact commands
- Dual-track: old transient narration and legacy parse path have explicit retirement conditions
