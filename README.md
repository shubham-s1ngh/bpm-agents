# bpm-ai

Camunda workflow agent framework for grounded operational diagnostics and controlled incident resolution.

## Current Status

- Project version: `0.1.0`
- Runtime stack:
  - Spring Boot `3.5.15-SNAPSHOT`
  - Java `21`
  - Camunda `8.9.0`
  - Spring AI
  - local Ollama-compatible LLM support

## Objective

This project is building a reusable framework where:

- multiple workflow strategies can be plugged in
- the agent resolves the correct workflow context first
- business identifiers are normalized per workflow
- Camunda orchestration APIs are the source of runtime truth
- the final user response is LLM-written but grounded to deterministic evidence
- incident resolution is explicit, controlled, and observable

The main design constraint is strict no-hallucination behavior for runtime facts.

## What It Does

The current implementation supports:

- workflow strategy resolution
- process-instance search by business identifier
- deep Camunda diagnostics
- child process traversal
- grounded report generation
- explicit incident resolution by:
  - incident key
  - process instance
- post-resolution verification polling
- retry observability:
  - resolution command attempts
  - verification checks

## Architecture

Core components:

- `src/main/java/com/shubham/dev/bpm_agent/chat/LlamaToolsTestController.java`
  - thin HTTP adapter
- `src/main/java/com/shubham/dev/bpm_agent/chat/service/CamundaAgentChatService.java`
  - main orchestration service
- `src/main/java/com/shubham/dev/bpm_agent/chat/CamundaDiagnosticTools.java`
  - Camunda-backed tool surface
- `src/main/java/com/shubham/dev/bpm_agent/chat/service/CamundaToolDispatchService.java`
  - tool extraction and dispatch
- `src/main/java/com/shubham/dev/bpm_agent/chat/service/CamundaEvidenceDigestService.java`
  - deterministic evidence normalization
- `src/main/java/com/shubham/dev/bpm_agent/chat/service/CamundaDiagnosticReportService.java`
  - grounded LLM report generation
- `src/main/java/com/shubham/dev/bpm_agent/chat/validation/CamundaReportGroundingValidator.java`
  - report grounding enforcement
- `src/main/java/com/shubham/dev/bpm_agent/strategy/WorkflowContextStrategy.java`
  - workflow extension contract

Detailed architecture:
- `docs/camunda-chat-agent-architecture.md`
- `docs/camunda-agent-context-framework.md`

## Current Workflow Strategy

Implemented strategy:

- `handleOrderId`

Current order strategy includes:

- order identifier normalization
- BPMN path interpretation
- child process context
- incident interpretation rules
- report shaping guidance

## Run Locally

### Requirements

- Java `21`
- Maven or Maven wrapper
- local Camunda 8.9 environment
- local LLM runtime configured in `application.yaml`

### Start the app

Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

Or:

```powershell
mvn spring-boot:run
```

## Build and Test

Compile:

```powershell
mvn -q -DskipTests compile
```

Run all tests:

```powershell
mvn -q test
```

Run focused tests:

```powershell
mvn -q "-Dtest=CamundaDiagnosticToolsTest,CamundaEvidenceDigestServiceTest,CamundaDiagnosticReportServiceTest" test
```

## API Shape

Current primary chat endpoint:

- `POST /api/llama/chat`

Typical prompt examples:

```json
{ "prompt": "check for this order id ORD-55422" }
```

```json
{ "prompt": "check for this order id ORD-55422 and retry incident" }
```

## Important Behavior

- The agent should not invent runtime state.
- Evidence normalization stays deterministic in Java.
- Final responses are written by the LLM, but constrained by a stable report contract and grounding validation.
- Incident resolution is mutation-only behavior and should happen only on explicit retry intent.
- Command acceptance is not treated as success unless post-check verification confirms the outcome.

## Documentation

- Architecture:
  - `docs/camunda-chat-agent-architecture.md`
- Context framework:
  - `docs/camunda-agent-context-framework.md`
- Strategy extension plan:
  - `docs/plans/strategy-driven-incident-resolution-plan.md`
- Release notes:
  - `docs/releases/release-notes-0.1.0.md`
- Agent implementation guide:
  - `AGENTS.md`

## Next Direction

The next major framework step is to move incident-resolution policy fully into workflow strategies so each workflow can decide:

- whether retry is allowed
- which incident types are retryable
- when retry should be blocked
- whether retry should happen by incident key or by process instance

That plan is documented in:

- `docs/plans/strategy-driven-incident-resolution-plan.md`
