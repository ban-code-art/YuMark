# Agent V2 Runtime Work Intent

Requested outcome: implement Agent V2 runtime so the document agent can plan, execute steps, capture evidence, replan once, and judge completion.

Scope:

- document agent runtime only
- task state, planner, executor, replanner, completion judge, and task progress UI

Non-goals:

- multi-agent collaboration
- background autonomous jobs
- unrestricted file or shell automation
- automatic document writes without user approval

Baseline refs:

- `docs/aegis/specs/2026-06-18-agent-v2-runtime-design.md`
- `docs/aegis/plans/2026-06-18-agent-v2-runtime-implementation-plan.md`
- `docs/aegis/baseline/2026-06-18-initial-baseline.md`
- `app/src/main/java/com/yumark/app/domain/usecase/ai/agent/AgentUseCases.kt`
- `app/src/main/java/com/yumark/app/presentation/ai/agent/AgentChatSheet.kt`

Impact statement:

- Affected owners: domain model, repository, Room schema, agent orchestration, agent UI.
- Compatibility boundary: chat mode and provider adapter contract stay stable; write approval remains mandatory.
- Main risks: Room migration drift, orchestration regression, UI state overlap.
