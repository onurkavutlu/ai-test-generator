# QA agent orchestration architecture (first controlled increment)

## Scope

This document describes the orchestration boundary introduced in
`feat/agent-orchestration-v2`. It is a planning and validation core, not a
replacement for the current generation or execution paths. It deliberately
does not claim that the application is production-ready.

The initial implementation provides immutable orchestration contracts,
deterministic plan construction, bounded step validation, and integration with
the existing agent and framework registries. It does not execute a generated
plan yet.

## Responsibilities and dependency direction

```text
controller / existing application services
                  |
                  v
        QaOrchestrator (plan + validate only)
                  |
       +----------+-----------+
       |                      |
       v                      v
AiAgentRegistry     FrameworkTestGeneratorRegistry
       |                      |
       v                      v
 existing agents       existing framework adapters
```

`DefaultQaOrchestrator` depends only on the two registry capabilities and the
deterministic planner. It has no dependency on Karate, REST Assured, Selenium,
Maven, WebDriver, database drivers, filesystem paths, Ollama, Qwen, OpenAI, or
provider-specific LangChain4J clients.

The existing `TestGenerationService`, `TestRunnerService`, comparison services,
and public controllers keep their current behaviour. The first production
integration is deliberately narrow: `POST /api/v1/runner/generate-from-response`
captures a live response, then creates and validates the orchestration plan
before persisting the generation request or starting async generation. A plan
rejection is returned as an explanatory HTTP 400, while the successful 202
response contract and observation-derived context remain unchanged.

## Agent, tool, framework, and model boundaries

- **Agent:** an existing `AiAgent` reasons within an `AiAgentRole`. The
  `AiAgentRegistry` remains the only role registry; the orchestrator only asks
  whether a role is available.
- **Tool:** a deterministic operation such as database access, log retrieval,
  execution, normalization, or comparison. This increment does not introduce a
  second, unused tool registry. A future step handler must expose an explicit
  tool contract before a tool-backed step becomes executable.
- **Framework:** an existing `FrameworkTestGenerator` adapter selected through
  `FrameworkTestGeneratorRegistry`. The orchestrator uses only the registry's
  capability query, never a concrete generator class.
- **LLM:** a reasoning provider, not an orchestration dependency. The current
  provider-neutral `LlmService` is retained for generation and analysis; its
  Ollama/OpenAI/LangChain4J implementations stay below that boundary. This
  orchestration core has no LLM call and does not require a local model.

The deterministic comparer remains unchanged: normalization and comparison
produce structured differences first. Existing optional AI analysis receives
only a mismatch when semantic interpretation is needed; it is not part of the
new plan executor.

## Implemented orchestration model

The `com.testgen.orchestration` package contains:

- `QaOrchestrator`, `OrchestrationRequest`, `OrchestrationResult`, and
  `OrchestrationContext` for a process-independent request/result boundary.
- `OrchestrationPlan` and `OrchestrationStep` for ordered, immutable plans with
  explicit orchestration and step identifiers.
- `OrchestrationStepType` as a closed vocabulary. This prevents an LLM or
  caller from naming an arbitrary executable action.
- `DeterministicOrchestrationPlanner`, which reuses `AgentRouting` for the
  established mandatory/optional agent order.
- `DefaultQaOrchestrator`, which validates the plan against
  `AiAgentRegistry` and `FrameworkTestGeneratorRegistry`.

Only these step types are supported in this increment:

1. `TEST_DESIGN`
2. `GENERATE_TEST_ARTIFACT`
3. `VALIDATE_ARTIFACT`

The enum also reserves future names such as `EXECUTE_TEST`, `QUERY_DATABASE`,
`FETCH_LOG`, `COMPARE`, and analysis/report steps. They are intentionally
rejected with `UnsupportedOrchestrationStepException` until a bounded handler
and the required deterministic tool/adapter are registered. A missing
mandatory agent or framework is rejected explicitly; a missing optional agent
is returned as a structured planning warning.

## Deterministic and LLM-assisted work

The planner makes no LLM decision. Step order is deterministic and comes from
the existing `AgentRouting` rules. Registry validation, framework selection,
JSON/XML normalization, HTTP/framework execution, database work, log
filtering, and comparison remain deterministic concerns.

LLM assistance remains appropriate for bounded reasoning tasks such as test
design, artifact generation, failure analysis, and semantic interpretation of
an already structured difference. It must not create arbitrary step names,
tool names, retries, shell commands, or framework execution actions.

## OCP direction

The contracts carry explicit orchestration, request, correlation, and step
identifiers plus timestamps. They hold no static mutable workflow state, JPA
entity reference, machine path, or local-model URL. This permits a future
durable state store and OCP worker/job execution model without changing the
core plan vocabulary.

Durable orchestration state, distributed locking/idempotency, retries, OCP Job
execution, database/log tools, Selenium Grid orchestration, and metrics are
not implemented here. They remain future delivery work and must be introduced
with a persistence and execution contract rather than process-local state.

## Verification

The tests cover deterministic routing order, framework independence,
mandatory/optional registry behaviour, unsupported-step rejection, duplicate
and incomplete plans, and value-object defaults. The runner contract tests
also prove that a rejected plan creates no generation request, while the
integration slice runs the real planner after observing the response and
preserves the endpoint's accepted response fields. The package is protected by
a JaCoCo line coverage gate of 90%.
