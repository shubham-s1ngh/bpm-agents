# bpm-ai

`bpm-ai` is a Spring Boot application that helps operators and consultants inspect Camunda 8 workflow instances, understand incidents, and apply controlled retry policy.

In plain terms, this project does three things:

1. It looks up a workflow instance from a natural-language prompt such as an `orderId`.
2. It collects live runtime evidence from Camunda, including variables, active flow elements, incidents, and child subprocesses.
3. It returns a grounded report, and when explicitly asked, it can attempt incident resolution using workflow-specific retry rules.

## Who This Is For

This project is useful for:

- developers working on Camunda-integrated workflows
- operators investigating stuck or failed process instances
- business consultants managing retry policy without changing Java code

## What The Application Includes

### Diagnostic agent

- natural-language prompt handling
- workflow strategy resolution
- process-instance search by business identifier
- deep runtime diagnostics
- child-process traversal

### Incident resolution

- explicit retry intent detection
- retry by incident key or process instance
- post-resolution verification polling
- workflow-specific allow/block/no-action policy

### Consultant rule management

- persisted incident-resolution rule catalog
- BPMN upload and draft rule suggestions
- browser-based rule editor

### Reporting

- grounded read-only diagnostic reports
- deterministic post-retry reports
- vector-backed workflow knowledge retrieval for better explanation

## Current Release Scope

Version: `0.1.0`

Current first-class workflow strategy:

- `handleOrderId`

Current order workflow support includes:

- `ORD-...` identifier extraction
- category translation
- subprocess-aware incident matching
- transient `500` retry handling
- blocking of deployment mismatch and payment `400` incidents

## Runtime Stack

- Spring Boot `3.5.15-SNAPSHOT`
- Java `21`
- Camunda `8.9.0`
- Spring AI
- Ollama-compatible chat model
- Ollama-compatible embedding model
- H2 + Flyway for consultant-managed rule persistence

## How It Works

### Read-only diagnosis flow

1. The user sends a prompt such as `check for this order id ORD-55448`.
2. The app resolves the matching workflow strategy.
3. It searches Camunda using the workflow’s business identifier.
4. It gathers process state, variables, incidents, flow elements, and child subprocess diagnostics.
5. It builds a grounded report from that evidence.

### Retry flow

1. The user explicitly asks to retry an incident.
2. The app diagnoses the active process instance first.
3. The workflow strategy evaluates whether retry is allowed, blocked, or skipped.
4. If allowed, the app sends the Camunda incident-resolution mutation.
5. It verifies the result before reporting success.

## Important Behavior

- Camunda is the source of truth for runtime state.
- The app should not invent instance keys, variables, incidents, or statuses.
- Evidence normalization stays deterministic in Java.
- Read-only reports can use the LLM for explanation, but only with grounded evidence.
- Incident-resolution reports are rendered deterministically from Camunda JSON.
- Mutation command acceptance is not treated as success unless verification confirms the outcome.

## Local URLs

- Main chat endpoint: `POST /api/llama/chat`
- Consultant rule editor: `http://localhost:8081/admin/incident-rules/index.html`
- H2 console: `http://localhost:8081/h2-console`

## Quick Start

### Requirements

- Java `21`
- Maven or Maven wrapper
- local Camunda 8.9 environment
- local Ollama-compatible runtime
- local embedding model, default `nomic-embed-text`

### Start the application

Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

Or:

```powershell
mvn spring-boot:run
```

## Build And Test

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

```powershell
mvn -q "-Dtest=CamundaAgentChatServiceTest,IncidentResolutionRuleCatalogServiceTest,WorkflowKnowledgeVectorStoreServiceTest" test
```

## Example Requests

Read-only diagnosis:

```json
{ "prompt": "check for this order id ORD-55422" }
```

Retry with incident resolution:

```json
{ "prompt": "check for this order id ORD-55422 and retry incident" }
```

Bulk retry:

```json
{ "prompt": "retry incidents for ORD-55421 and ORD-55422" }
```

## Configuration Notes

Most local behavior is controlled from [application.yaml](src/main/resources/application.yaml), including:

- Camunda connection settings
- Ollama chat model configuration
- Ollama embedding model configuration
- vector retrieval toggle and top-K value
- incident verification polling
- local mock service failure simulation
- H2 datasource and Flyway migration setup

## Main Source Areas

- [LlamaToolsTestController](src/main/java/com/shubham/dev/bpm_agent/chat/LlamaToolsTestController.java)
  - HTTP entry point
- [CamundaAgentChatService](src/main/java/com/shubham/dev/bpm_agent/chat/service/CamundaAgentChatService.java)
  - session orchestration
- [CamundaDiagnosticTools](src/main/java/com/shubham/dev/bpm_agent/chat/CamundaDiagnosticTools.java)
  - Camunda-facing diagnostics and mutation tools
- [CamundaDiagnosticReportService](src/main/java/com/shubham/dev/bpm_agent/chat/service/CamundaDiagnosticReportService.java)
  - final reporting
- [OrderWorkflowStrategy](src/main/java/com/shubham/dev/bpm_agent/strategy/OrderWorkflowStrategy.java)
  - current workflow-specific behavior
- [WorkflowKnowledgeVectorStoreService](src/main/java/com/shubham/dev/bpm_agent/strategy/retrieval/WorkflowKnowledgeVectorStoreService.java)
  - workflow knowledge indexing and retrieval

## Documentation

- Architecture:
  - [docs/camunda-chat-agent-architecture.md](docs/camunda-chat-agent-architecture.md)
- Context framework:
  - [docs/camunda-agent-context-framework.md](docs/camunda-agent-context-framework.md)
- Release notes:
  - [docs/releases/release-notes-0.1.0.md](docs/releases/release-notes-0.1.0.md)
- Strategy plan:
  - [docs/plans/strategy-driven-incident-resolution-plan.md](docs/plans/strategy-driven-incident-resolution-plan.md)
- Agent implementation guide:
  - [AGENTS.md](AGENTS.md)

## Current Limits

- `searchProcessInstances` still searches by variable and does not yet enforce workflow `processId` at the tool level.
- bulk retry is currently order-workflow-specific
- the consultant UI is intentionally minimal and static

## Next Direction

The next major step is to generalize the current design so additional workflows can contribute:

- workflow-specific business identifier extraction
- workflow-specific BPMN knowledge indexing
- workflow-specific batch retry behavior
- workflow-specific report interpretation guidance
