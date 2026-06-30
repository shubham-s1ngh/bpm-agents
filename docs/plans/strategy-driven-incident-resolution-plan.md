# Strategy-Driven Incident Resolution Plan

## Objective

Move incident-resolution policy out of generic orchestration code and into workflow strategies so the framework can support multiple workflows with different retry/resolution rules without modifying core services.

## Why This Change

Current design is good for:
- workflow selection
- business identifier normalization
- BPMN interpretation
- report interpretation

Current design is still generic in the wrong place for:
- whether an incident is safe to retry
- whether retry should be blocked for a specific error type
- whether retry should happen by `incidentKey` or `processInstanceKey`
- whether a deployment/configuration issue should be retried at all
- whether the agent should return guidance instead of executing a mutation

Those are workflow-policy decisions and should be strategy-owned.

## Target Design

### 1. Extend `WorkflowContextStrategy`

Add workflow-policy methods for incident handling, for example:

- `supportsIncidentResolution()`
- `preferredResolutionMode()`
- `canAttemptResolution(IncidentResolutionContext context)`
- `buildResolutionDecision(IncidentResolutionContext context)`
- `buildResolutionGuidance(IncidentResolutionContext context)`

The core point is not the exact names. The core point is that the strategy returns policy, not the orchestration service.

### 2. Introduce an Incident Resolution Policy Model

Add small explicit models under a new package, for example:

- `chat.model.incident.IncidentResolutionContext`
- `chat.model.incident.IncidentResolutionDecision`
- `chat.model.incident.IncidentResolutionMode`

Suggested responsibilities:

- `IncidentResolutionContext`
  - user prompt
  - selected process definition ID
  - process instance key
  - active incidents
  - current diagnostic snapshot

- `IncidentResolutionDecision`
  - `allowed`
  - `mode`
  - `reason`
  - `userFacingGuidance`

- `IncidentResolutionMode`
  - `BY_INCIDENT_KEY`
  - `BY_PROCESS_INSTANCE`
  - `BLOCKED`
  - `NO_ACTION`

### 3. Keep `CamundaAgentChatService` Generic

Refactor the orchestration flow so it does not hardcode retry policy.

It should:
- detect explicit retry intent
- resolve workflow strategy
- gather current active incident evidence
- ask the strategy for a resolution decision
- execute or block based on that decision

It should not decide on its own:
- whether a `CALLED_ELEMENT_ERROR` should be retried
- whether deployment-missing incidents should be blocked
- whether one workflow allows auto-resolution while another does not

### 4. Keep `CamundaDiagnosticTools` Infrastructure-Focused

`CamundaDiagnosticTools` should remain responsible for:
- calling Camunda APIs
- polling for stabilization
- returning grounded mutation/diagnostic payloads

It should not own workflow business policy.

### 5. Make `OrderWorkflowStrategy` the First Policy Owner

Use `OrderWorkflowStrategy` as the first implementation.

Examples of policy it should eventually own:
- `CALLED_ELEMENT_ERROR` caused by missing called BPMN deployment may be marked `BLOCKED`
- payment-related transient incidents may be marked `ALLOWED`
- process-instance resolution may be preferred over incident-key resolution for order flows

This gives one concrete reference implementation before other workflow strategies are added.

## Suggested Implementation Steps

### Phase 1: Policy Abstractions

1. Add incident policy model classes
2. Extend `WorkflowContextStrategy` with default incident-resolution policy methods
3. Add a generic fallback policy for strategies that do not override incident behavior

### Phase 2: Orchestration Integration

1. Refactor `CamundaAgentChatService` to assemble an `IncidentResolutionContext`
2. Ask the selected strategy for an `IncidentResolutionDecision`
3. Execute mutation only when decision allows it
4. Return workflow-specific guidance when decision blocks resolution
5. Support deterministic bulk retry orchestration where one prompt can target multiple workflow identifiers without relying on the model loop to improvise batching

### Phase 3: Order Strategy Policy

1. Implement order-specific incident rules in `OrderWorkflowStrategy`
2. Distinguish retryable vs non-retryable order incidents
3. Add explicit guidance for deployment mismatch incidents

### Phase 4: Reporting

1. Include policy decision summary in retry reports
2. Show whether resolution was:
   - allowed
   - blocked
   - skipped because no active incident existed
3. Keep all user-facing statements grounded to evidence plus strategy policy

## Acceptance Criteria

- Core services do not hardcode workflow-specific retry policy
- Adding a new workflow strategy does not require changing generic retry orchestration
- Order workflow can block non-sensical retry attempts through strategy policy
- Reports explain whether retry was attempted, blocked, or skipped
- Existing diagnostic behavior remains grounded and stable

## Risks

- Overloading strategy classes with too much runtime logic
- Mixing evidence interpretation with mutation policy
- Accidentally moving generic infrastructure behavior into strategies

## Guardrails

- Strategy decides policy
- Tool layer executes infrastructure calls
- Evidence layer stays deterministic
- Report layer stays grounded
- Orchestration composes these pieces without embedding workflow-specific business rules

## Recommended Next Step

Implement Phase 1 only first:
- policy model classes
- strategy interface extension
- one `OrderWorkflowStrategy` policy stub

That is the smallest change that opens the design for extension without forcing a large behavioral rewrite.
