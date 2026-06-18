# Release Notes - `0.1.0`

## Overview

`0.1.0` is the first structured release of the Camunda workflow agent framework. This release stabilizes the core architecture for workflow-context resolution, Camunda-backed diagnostics, grounded LLM reporting, and controlled incident-resolution flows.

## Highlights

### 1. Refactored Chat-Agent Architecture

The original controller-heavy implementation was split into focused components:

- `LlamaToolsTestController`
- `CamundaAgentChatService`
- `CamundaToolDispatchService`
- `CamundaDiagnosticReportService`
- `CamundaEvidenceDigestService`
- `CamundaReportGroundingValidator`

This improves:
- separation of concerns
- testability
- extensibility for multiple workflow strategies

### 2. Workflow Strategy Foundation

Workflow-specific behavior is now strategy-driven through:

- `WorkflowContextStrategy`
- `WorkflowStrategyRegistry`
- `OrderWorkflowStrategy`

Current strategy support includes:
- workflow identification
- business identifier normalization
- BPMN interpretation rules
- workflow-specific report guidance

### 3. Grounded Evidence-Based Reporting

The final report remains LLM-written, but is now constrained by:

- deterministic evidence normalization
- canonical evidence digests
- grounding validation
- sanitize fallback when the model leaks unsupported values

This reduces hallucination risk while keeping the response readable.

### 4. Dynamic Camunda Diagnostics

Diagnostics now include:
- process-instance lookup
- variable lookup
- incident lookup
- flow element inspection
- child process traversal

The diagnostic layer can explain current runtime position using Camunda evidence plus workflow BPMN context.

### 5. Incident Resolution Support

Incident mutation support was redesigned and hardened:

- `resolveIncidentByKey`
- `resolveIncidentsByProcessInstance`

Key behaviors:
- mutation allowed only on explicit retry intent
- post-resolution verification polling
- active-incident filtering by incident state
- readable mutation outcome reports
- current diagnostic snapshot included after resolution or no-action outcomes

### 6. Retry Observability

Retry-related responses now expose:
- `resolutionCommandAttempts`
- `verificationChecks`

This makes it clear whether the system:
- actually sent a resolution command
- only checked for active incidents
- retried via polling versus mutation

## Behavior Improvements

### Diagnostic Prompts

Prompts such as:

```json
{ "prompt": "check for this order id ORD-55422" }
```

now produce more stable grounded reports with:
- consistent markdown headings
- evidence-backed variables
- current flow elements
- child process visibility
- incident sections only when active incidents exist

### Retry Prompts

Prompts such as:

```json
{ "prompt": "check for this order id ORD-55422 and retry incident" }
```

now:
- find the active process instance deterministically
- resolve incidents at the process-instance level
- poll for stabilization
- report retry outcome with grounded current-state diagnostics

## Current Known Limits

- `searchProcessInstances` still searches by variable and does not yet enforce workflow `processId` at the tool level.
- incident-resolution policy is not yet strategy-driven; generic orchestration still decides whether retry should proceed.
- the project artifact is now released as `0.1.0`, but the Spring parent remains on `3.5.15-SNAPSHOT`.

## Documentation Added or Updated

- `docs/camunda-chat-agent-architecture.md`
- `docs/camunda-agent-context-framework.md`
- `AGENTS.md`
- `docs/plans/strategy-driven-incident-resolution-plan.md`

## Test Coverage Added

Focused tests now cover:
- agent routing behavior
- evidence digest normalization
- report contract behavior
- grounding validation
- incident-resolution outcome semantics

Representative test files:
- `src/test/java/com/shubham/dev/bpm_agent/chat/CamundaDiagnosticToolsTest.java`
- `src/test/java/com/shubham/dev/bpm_agent/chat/service/CamundaAgentChatServiceTest.java`
- `src/test/java/com/shubham/dev/bpm_agent/chat/service/CamundaEvidenceDigestServiceTest.java`
- `src/test/java/com/shubham/dev/bpm_agent/chat/service/CamundaDiagnosticReportServiceTest.java`
- `src/test/java/com/shubham/dev/bpm_agent/chat/validation/CamundaReportGroundingValidatorTest.java`

## Recommended Next Step

The next architectural step should be to move incident-resolution policy into workflow strategies so each workflow can decide:
- whether retry is allowed
- which incident types are retryable
- whether retry should be blocked and replaced with guidance
- which resolution mode is preferred
