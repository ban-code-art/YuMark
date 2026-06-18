# Agent V2 Runtime Design

Date: 2026-06-18
Status: approved for planning
Topic: upgrade YuMark's document agent from a single-loop tool caller into a task-driven planner/executor runtime

## 1. Goal

YuMark's current agent can call tools and propose document writes, but it still behaves like a thin ReAct loop wrapped inside a single use case. The target of Agent V2 is not to imitate Claude Code or Codex feature-for-feature. The target is to make YuMark's document agent materially more capable at:

- decomposing a multi-step user request
- executing steps with explicit state
- recovering from tool failure or missing information
- deciding completion based on task success criteria instead of stream termination
- showing users what the agent is doing and why

This design is scoped to the document agent only. It does not attempt to turn YuMark into a general code agent or IDE agent.

## 2. Problem Statement

Current baseline constraints:

- agent orchestration is concentrated in `domain/usecase/ai/agent/AgentUseCases.kt`
- the agent primarily uses one in-memory loop over streamed model output
- there is no explicit task model, plan state, or evidence model
- write proposals still terminate the flow instead of participating in a broader task runtime
- completion is inferred from the model stopping, not from structured task completion criteria
- context and tool usage quality are bounded by prompt quality and by a small set of runtime guards

Recent fixes improved protocol safety and write preview behavior, but they did not change the shape of the runtime. Agent V2 addresses that architectural gap.

## 3. Objectives

Agent V2 must provide:

1. structured task planning before execution for non-trivial agent requests
2. explicit step state tracking during execution
3. evidence capture so replanning does not depend on ephemeral streamed text alone
4. bounded replanning when a step fails or becomes blocked
5. completion evaluation against success criteria
6. UI-visible task progress for trust and debuggability

## 4. Non-Goals

This version explicitly does not include:

- multi-agent collaboration
- background long-running autonomous jobs
- unrestricted file-system or shell automation
- automatic multi-document write execution without user approval
- replacement of the existing standard chat mode
- full semantic retrieval system in the first implementation wave

## 5. Proposed Architecture

Agent V2 introduces a task-driven runtime split into four core roles:

- `Planner`
- `Executor`
- `Replanner`
- `Completion Judge`

These roles are coordinated by the existing user-entry orchestration path, but are no longer all embedded inside a single free-form streaming loop.

### 5.1 Planner

Responsibility:

- transform a user goal into a structured task plan
- define success criteria, step order, and fallback intent
- avoid direct tool execution

Output:

- `AgentTask`
- ordered `AgentTaskStep` list

### 5.2 Executor

Responsibility:

- execute the current step only
- call tools needed for that step
- store evidence and result summaries
- decide whether the current step is done, blocked, or failed

### 5.3 Replanner

Responsibility:

- update only the remaining plan when execution is blocked or fails
- preserve already-completed work
- avoid resetting the entire task unless strictly necessary

### 5.4 Completion Judge

Responsibility:

- inspect task goal, success criteria, step states, and evidence
- decide `COMPLETED`, `BLOCKED`, or `FAILED`
- prevent false positives where the model merely stops speaking

## 6. Data Model

Agent V2 adds explicit runtime state instead of relying on message text alone.

### 6.1 AgentTask

Purpose:

- represent one agent task bound to one conversation

Suggested fields:

- `id`
- `conversationId`
- `goal`
- `status`
- `createdAt`
- `updatedAt`
- `currentStepId`
- `planVersion`
- `finalSummary`
- `blockingReason`

Suggested statuses:

- `PLANNING`
- `EXECUTING`
- `REPLANNING`
- `BLOCKED`
- `COMPLETED`
- `FAILED`

### 6.2 AgentTaskStep

Purpose:

- represent one planned unit of work

Suggested fields:

- `id`
- `taskId`
- `title`
- `description`
- `status`
- `order`
- `dependsOnStepIds`
- `completionCriteria`
- `resultSummary`
- `toolHints`

Suggested statuses:

- `PENDING`
- `RUNNING`
- `DONE`
- `BLOCKED`
- `FAILED`
- `SKIPPED`

### 6.3 AgentEvidence

Purpose:

- persist execution observations and tool outcomes for later reasoning

Suggested fields:

- `id`
- `taskId`
- `stepId`
- `type`
- `content`
- `sourceTool`
- `createdAt`

Suggested evidence types:

- `SEARCH_RESULT`
- `DOCUMENT_SNAPSHOT`
- `TOOL_ERROR`
- `DECISION_NOTE`
- `ACTION_PROPOSAL`

### 6.4 AgentExecutionCheckpoint

Purpose:

- support bounded recovery and summarization of in-flight runtime state

Suggested fields:

- `taskId`
- `lastCompletedStepId`
- `lastToolCall`
- `lastToolResult`
- `retryCount`
- `contextDigest`

This can start embedded inside task persistence if that reduces initial schema cost.

## 7. State Machine

Agent V2 runtime states:

1. `PLANNING`
2. `EXECUTING`
3. `REPLANNING`
4. `BLOCKED`
5. `COMPLETED`
6. `FAILED`

Transitions:

- new task enters `PLANNING`
- successful plan generation transitions to `EXECUTING`
- current step success keeps the task in `EXECUTING` and advances `currentStepId`
- blocked or failed step can move to `REPLANNING`
- replanning failure can end in `BLOCKED` or `FAILED`
- completion judge can end in `COMPLETED`
- retry or replan budgets can force `FAILED`

## 8. Runtime Protocol

The runtime uses separate model-facing protocols for planning and execution rather than one free-form prompt.

### 8.1 Planner Protocol

The planner must return structured JSON containing at least:

- `goal`
- `success_criteria`
- `steps`
- `assumptions`
- `risks`

Each step must include at least:

- `id`
- `title`
- `purpose`
- `suggested_tools`
- `completion_criteria`
- `fallback`

Planner constraints:

- do not invent document IDs
- do not skip retrieval when the task depends on document contents
- do not execute write operations
- prefer search, then read, then propose edits

### 8.2 Executor Protocol

The executor is scoped to one current step and should emit one of three outcome classes:

- `TOOL_CALL`
- `STEP_DONE`
- `STEP_BLOCKED`

This keeps runtime control in Kotlin instead of requiring fragile free-form interpretation from natural language.

### 8.3 Replanner Protocol

The replanner receives:

- original goal
- current plan
- completed steps
- blocked or failed step
- accumulated evidence
- failure reason

It returns:

- steps to keep
- steps to remove
- steps to add
- updated order
- rationale for the change

### 8.4 Completion Judge Protocol

The completion judge receives:

- original goal
- success criteria
- step states
- evidence
- final response or action proposal

It returns one of:

- `COMPLETED`
- `BLOCKED`
- `FAILED`

with a reason.

## 9. Integration with Current Code

This design intentionally preserves existing provider and tool foundations.

### 9.1 Reused Components

The following stay in place:

- `AiAdapterFactory`
- provider adapters in `data/ai/adapters/*`
- `DocumentContextTools`
- `ExecuteDocumentToolUseCase`
- `ExecuteAgentActionUseCase`
- conversation/message persistence
- existing agent and chat entry surfaces

### 9.2 New Use Cases

Introduce:

- `PlanAgentTaskUseCase`
- `ExecuteAgentTaskUseCase`
- `ReplanAgentTaskUseCase`
- `EvaluateTaskCompletionUseCase`
- `SummarizeTaskProgressUseCase`

`SendAgentMessageUseCase` becomes a coordinator that:

1. saves the user message
2. creates or resumes a task
3. invokes planning when needed
4. invokes execution
5. invokes replanning when required
6. evaluates completion
7. emits UI-oriented state changes

### 9.3 Repository Boundary

Add a dedicated `AgentTaskRepository` abstraction with minimal initial operations:

- `createTask`
- `getTaskByConversationId`
- `updateTask`
- `replaceSteps`
- `appendEvidence`
- `markStepStatus`

Implementation guidance:

- V1 may store this in lightweight JSON fields or dedicated Room tables
- the use-case boundary must exist from the start so storage can evolve without reworking orchestration logic

## 10. UI Impact

UI changes stay focused and bounded.

Expose a task-oriented progress state showing:

- current task goal
- step list
- current step highlight
- replanning state
- blocking reason
- final completion summary

This should replace the current tool-narration-only view as the primary agent progress model, while keeping existing message rendering intact.

## 11. Delivery Phases

### Phase 1: Runtime Skeleton

- add task, step, and evidence models
- add `AgentTaskRepository`
- refactor orchestration entry to support explicit runtime state

Outcome:

- agent has a dedicated task lifecycle

### Phase 2: Planner

- add structured plan generation
- expose plan state in the UI

Outcome:

- users can see the agent working through a plan instead of an opaque stream

### Phase 3: Executor

- step-scoped execution
- evidence capture
- explicit done/blocked outcomes

Outcome:

- task progression becomes inspectable and enforceable

### Phase 4: Replanner and Completion Judge

- bounded automatic replanning
- structured completion decision

Outcome:

- agent becomes resilient enough for longer document workflows

## 12. Risks and Controls

### 12.1 Context Growth

Risk:

- plan state and evidence can overwhelm token budgets

Control:

- maintain summaries and step-focused context slices
- do not resend the entire task history every turn

### 12.2 Structured Output Fragility

Risk:

- planner or replanner JSON may be malformed

Control:

- strict parsing
- validation and rejection of missing critical fields
- safe failure path back to user-visible error or block state

### 12.3 Regression on Simple Tasks

Risk:

- always planning can slow simple requests

Control:

- support a fast path for clearly simple requests
- reserve explicit planning for non-trivial tasks

### 12.4 UI Overload

Risk:

- exposing too much internal state makes the experience noisy

Control:

- show goal, steps, current state, and block reason only
- keep raw evidence internal by default

## 13. Testing Strategy

### 13.1 State Machine Tests

Validate:

- task enters and leaves each runtime state correctly
- step progression only occurs through allowed transitions
- replan and failure budgets are enforced

### 13.2 Planner Parsing Tests

Validate:

- well-formed plans parse
- missing required fields fail safely
- invalid tools are rejected

### 13.3 Executor Tests

Validate:

- current-step-only execution
- evidence capture
- repeated-tool-call guards
- write proposals still require approval

### 13.4 Replanner Tests

Validate:

- completed work is preserved
- only remaining work is changed
- goal drift is prevented

### 13.5 Integration Scenarios

Minimum scenarios:

- locate document, read it, summarize it
- locate document, propose edit, wait for approval
- search failure followed by replanning
- tool failure leading to blocked state
- multi-step task completion across several steps

## 14. Acceptance Criteria

Agent V2 V1 is successful when:

1. the agent no longer relies on implicit stream termination as the main completion signal
2. non-trivial tasks show an explicit plan and active step
3. tool failure yields a structured blocked or replanning path
4. at least one bounded automatic replanning path exists
5. write actions still remain behind user approval
6. simple task success rate does not materially regress

## 15. Compatibility Boundaries

Must remain stable during implementation:

- standard chat mode
- provider adapter public usage shape
- document create/save/load use cases
- user approval gate for writes
- existing conversation and message rendering outside the agent task UI additions

## 16. Recommended Implementation Order

1. task state models and repository
2. planner
3. executor
4. UI task panel
5. replanner
6. completion judge

## 17. Draft Artifacts

### TaskIntentDraft

- Outcome: make the document agent materially more capable at multi-step planning and execution
- Scope: agent runtime only, not general coding-agent behavior
- Risks: state complexity, context growth, planner fragility, regression on simple flows

### BaselineReadSetHint

- `docs/ARCHITECTURE.md`
- `docs/agent-gap-analysis-2026-06-17.md`
- `app/src/main/java/com/yumark/app/domain/usecase/ai/agent/AgentUseCases.kt`
- `app/src/main/java/com/yumark/app/presentation/ai/agent/AgentChatSheet.kt`
- `app/src/main/java/com/yumark/app/data/local/db/entity/AiEntities.kt`
- `app/src/main/java/com/yumark/app/domain/usecase/ai/DocumentContextTools.kt`

### ImpactStatementDraft

- Affected layers: domain orchestration, persistence, adapter-facing task control, agent UI
- Invariants: provider adapters remain reusable, write approval remains mandatory, chat mode remains separate
- Non-goals: multi-agent system, unrestricted autonomy, background agent jobs
