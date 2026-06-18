# YuMark Initial Baseline Snapshot

Date: 2026-06-18
Reviewer: Codex
Scope: current Android app baseline before Agent V2 planning

## 1. Project Structure

Top-level areas:

- `app/`: Android application source
- `docs/`: design, review, and release documentation
- `gradle/`, `build.gradle.kts`, `settings.gradle.kts`: build system
- release artifacts and release notes at repo root

Key source entry points:

- `app/src/main/java/com/yumark/app/YuMarkApplication.kt`
- `app/src/main/java/com/yumark/app/MainActivity.kt`
- `app/src/main/java/com/yumark/app/presentation/navigation/YuMarkNavGraph.kt`

## 2. Tech Stack

- Kotlin
- Android app with Jetpack Compose
- Clean Architecture + MVVM
- Hilt for dependency injection
- Room for local structured persistence
- DataStore for settings/config
- Ktor client for AI provider and update network access
- JUnit 5, MockK, Truth, Turbine, coroutines test for unit testing

## 3. Ownership Mapping

- Editor UI and editing workflow:
  - `presentation/editor/*`
- AI conversation and agent UI:
  - `presentation/ai/*`
- AI runtime/provider integration:
  - `data/ai/*`
  - `data/ai/adapters/*`
- Agent orchestration:
  - `domain/usecase/ai/agent/AgentUseCases.kt`
- AI document tools:
  - `domain/usecase/ai/DocumentContextTools.kt`
  - `domain/usecase/ai/ExecuteDocumentToolUseCase.kt`
- Persistence:
  - `data/local/db/*`
  - `data/repository/*`

## 4. Contract Inventory

Key current contracts:

- `AiApiAdapter.sendChatStream(messages, config, tools): Flow<StreamEvent>`
- `ConversationRepository` for conversation/message persistence and observation
- `DocumentRepository` and document use cases for document load/save/create
- `StreamEvent` as the provider-stream event model
- `ChatMessage`, `ToolCall`, `AiTool` as provider-facing normalized models
- `AgentAction` as the user-confirmed write proposal contract

## 5. Dependency Direction Convention

- Presentation depends on domain use cases and domain models
- Domain depends on repository interfaces and pure models
- Data implements repository interfaces and provider integrations
- Provider adapters should not depend on presentation

## 6. Test System

- Unit tests under `app/src/test/java`
- Existing targeted coverage around AI adapters, agent use cases, view models, and parser helpers
- No evidence of broad end-to-end agent runtime integration tests yet

## 7. Build and Deploy

- Gradle Android build
- APK release artifacts created locally and documented in repo
- GitHub release process documented in repo root docs

## 8. Known Anti-Patterns

- Large orchestration concentration inside `SendAgentMessageUseCase`
- Agent runtime state spread across streaming text, message rows, and conversation status without a dedicated task model
- Prompt/context policy split across multiple AI entry points
- Write proposals historically coupled to textual protocol fallback

## 9. Last Review Findings

- 2026-06-17 agent gap analysis documented missing or partial agent capabilities
- 2026-06-18 local fixes addressed:
  - missing-config status reset
  - view model rebinding/content refresh issues
  - cross-document diff base handling
  - Claude/Gemini tool-call protocol support gaps

## 10. Compatibility Boundaries

The following must not break during Agent V2 work:

- standard chat mode behavior
- provider adapter public entry contract
- document persistence and existing save/create use cases
- explicit user confirmation gate for write actions
- current conversation and message rendering for non-agent flows
