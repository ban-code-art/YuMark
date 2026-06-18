# Agent Runtime Review Fix Plan

- Date: 2026-06-18
- Scope: Agent V2 runtime review findings
- Status: Active fix plan

## 1. Review Findings

### F1. Planner prompt test and implementation disagree

Evidence:

- `SendAgentMessageUseCaseTest.planner prompt does not plan create document tool for underspecified creation requests` fails.
- The test asserts the planner prompt must not mention `save location`.
- `buildPlannerSystemPrompt` says document creation should not require save location, title, outline, or depth before planning.

Decision:

- Keep the newer product behavior: creating a document should not be blocked only because save location, title, outline, or depth is missing.
- The agent may infer those details and create a pending write proposal.
- Fix the test and wording so planner and agent prompts express the same rule.

Acceptance:

- Planner prompt test passes.
- Agent prompt no longer contains a contradictory hard rule that says insufficient information always forbids `create_document`.

### F2. `ExecuteAgentTaskUseCase` is tested but not used in the real send loop

Evidence:

- `SendAgentMessageUseCase` injects `ExecuteAgentTaskUseCase`, but production code does not call it.
- The real loop calls `ExecuteDocumentToolUseCase` directly.
- `ExecuteAgentTaskUseCaseTest` verifies evidence capture, blocked status, and write proposals, but those contracts do not govern the real path.

Decision:

- Make `SendAgentMessageUseCase` call `ExecuteAgentTaskUseCase` for each completed tool-call batch.
- Keep provider adapter contracts and write-approval behavior unchanged.
- Keep UI step events in `SendAgentMessageUseCase`, but delegate authoritative task status and evidence capture to `ExecuteAgentTaskUseCase`.

Acceptance:

- A tool failure in the real send loop marks the task and step blocked.
- A write tool in the real send loop becomes an action proposal without executing the document write.
- Evidence capture is owned by `ExecuteAgentTaskUseCase`, not duplicated in `SendAgentMessageUseCase`.

### F3. Stop leaves task progress running

Evidence:

- `AgentChatViewModel.stop()` cancels streaming and resets the conversation to `IDLE`.
- It does not update the persisted `AgentTask`.
- The task progress panel hides completed tasks only, so an interrupted task can remain visible as `EXECUTING`.

Decision:

- On user stop, mark the current task as `BLOCKED` with a user-cancelled reason if it is still active.
- This preserves history without pretending the task completed.

Acceptance:

- Stopping an active agent run updates task status away from `EXECUTING`.
- The task progress UI shows a terminal interrupted state instead of stale in-flight progress.

## 2. Implementation Order

1. Update tests for the chosen document-creation prompt semantics and watch them fail where production wording is still contradictory.
2. Fix prompt wording.
3. Add/adjust tests for real send-loop delegation to `ExecuteAgentTaskUseCase`.
4. Rewire `SendAgentMessageUseCase` to use `ExecuteAgentTaskUseCase`.
5. Add/adjust tests for `stop()` task finalization.
6. Run targeted agent tests.
7. Run full debug unit tests if targeted tests pass.

## 3. Verification Commands

Targeted:

```powershell
cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest" --tests "com.yumark.app.presentation.ai.agent.AgentChatViewModelTest" --tests "com.yumark.app.domain.usecase.ai.agent.ExecuteAgentTaskUseCaseTest"
```

Full:

```powershell
cmd /c gradlew.bat :app:testDebugUnitTest
```

## 4. Non-goals

- Do not redesign provider adapters.
- Do not add automatic replanning in this fix unless needed to preserve existing behavior.
- Do not change write approval semantics.
- Do not alter Room schema unless a test proves it is required.

