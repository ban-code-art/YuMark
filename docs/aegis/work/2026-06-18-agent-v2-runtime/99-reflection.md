# Agent V2 Runtime Reflection

The remaining runtime gap from the prior checkpoint was completion finalization. The send loop now routes normal answer and write-proposal exits through a bounded completion judge, then persists `COMPLETED`, `BLOCKED`, or `FAILED` task state with a final summary and optional blocking reason.

Runtime guard exits are finalized directly because repeated tool calls and max-step exhaustion are control-flow failures already known by Kotlin. This keeps provider contracts stable and avoids asking the model to re-interpret a guard condition after the runtime has stopped it.

The completion judge receives an in-memory snapshot of the latest planned step state and result summaries. This avoids stale `PENDING` step context without adding a second repository read inside the hot path.

Residual risk: the branch still has a broad dirty worktree that includes adjacent attachment, diff, sidebar, and release-note changes. Unit and compile checks passed, but release packaging and manual device UX verification remain separate closure steps.
