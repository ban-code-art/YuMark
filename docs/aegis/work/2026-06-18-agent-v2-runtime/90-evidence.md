# Agent V2 Runtime Evidence

## Completion Finalization Slice

RED:

- `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.finalizes*"` failed because the send loop stopped at conversation completion and did not invoke the completion judge.
- `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.completion*"` failed because the completion judge received the initial pending plan instead of the latest step status/result summary.

GREEN:

- `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.finalizes*"` produced `BUILD SUCCESSFUL`.
- `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.completion*"` produced `BUILD SUCCESSFUL`.

Regression:

- `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest"` produced `BUILD SUCCESSFUL`.
- `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.PlanAgentTaskUseCaseTest" --tests "com.yumark.app.domain.usecase.ai.agent.ExecuteAgentTaskUseCaseTest" --tests "com.yumark.app.domain.usecase.ai.agent.ReplanAgentTaskUseCaseTest" --tests "com.yumark.app.domain.usecase.ai.agent.EvaluateTaskCompletionUseCaseTest" --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest" --tests "com.yumark.app.presentation.ai.agent.AgentChatViewModelTest" --tests "com.yumark.app.data.ai.adapters.ProviderToolMessageFormattingTest"` produced `BUILD SUCCESSFUL`.
- `cmd /c gradlew.bat :app:testDebugUnitTest` produced `BUILD SUCCESSFUL`.
- `cmd /c gradlew.bat :app:compileDebugKotlin` produced `BUILD SUCCESSFUL`.

## Evidence Boundary

- Covered: planner parsing, executor/replanner contracts, completion parser, send-loop completion finalization, blocked finalization, latest step-state judge input, multi-turn document-creation clarification, task progress view model, provider tool-message formatting, and full debug unit suite.
- Not covered: release build packaging, instrumented Android/device UI checks, live provider calls, and manual approval-flow UX.

## Feedback Fix Slice

RED:

- `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.*prompt*" --tests "com.yumark.app.presentation.ai.agent.AgentChatViewModelTest.completed*"` failed because the create-document prompt did not require clarification and completed task progress remained visible.

GREEN:

- `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.*prompt*" --tests "com.yumark.app.presentation.ai.agent.AgentChatViewModelTest.completed*"` produced `BUILD SUCCESSFUL`.
- `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest" --tests "com.yumark.app.presentation.ai.agent.AgentChatViewModelTest"` produced `BUILD SUCCESSFUL`.
- `cmd /c gradlew.bat :app:testDebugUnitTest` produced `BUILD SUCCESSFUL`.

## Multi-turn Clarification Slice

RED:

- `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.document creation clarification follow up*"` failed because follow-up answers after a creation clarification did not resume the original document-creation intent.
- `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.document creation clarification accumulates*"` failed because earlier partial answers were lost when the user supplied the remaining save location in a later turn.

GREEN:

- `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.document creation clarification*"` produced `BUILD SUCCESSFUL`.
- `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest" --tests "com.yumark.app.presentation.ai.agent.AgentChatViewModelTest"` produced `BUILD SUCCESSFUL`.
- `cmd /c gradlew.bat :app:testDebugUnitTest` produced `BUILD SUCCESSFUL`.

## Clarification Semantics Correction Slice

RED:

- `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.*creation*"` failed because the runtime still treated title, structure, and depth as required user-supplied slots instead of agent-inferred choices.

GREEN:

- `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest.*creation*"` produced `BUILD SUCCESSFUL` after only save/create location remained a hard clarification requirement.
- `cmd /c gradlew.bat :app:testDebugUnitTest --tests "com.yumark.app.domain.usecase.ai.agent.SendAgentMessageUseCaseTest" --tests "com.yumark.app.presentation.ai.agent.AgentChatViewModelTest"` produced `BUILD SUCCESSFUL`.
- `cmd /c gradlew.bat :app:testDebugUnitTest` produced `BUILD SUCCESSFUL`.
