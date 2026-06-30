# bpm-ai

Camunda workflow agent framework for grounded operational diagnostics, consultant-managed retry policy, and controlled incident resolution.

## Current Status

- Project version: `0.1.0`
- Runtime stack:
  - Spring Boot `3.5.15-SNAPSHOT`
  - Java `21`
  - Camunda `8.9.0`
  - Spring AI
  - Ollama-compatible chat and embedding models
  - H2 + Flyway for consultant-managed rule storage

## What This Release Includes

- workflow strategy resolution for `handleOrderId`
- business identifier normalization for `orderId`
- deep Camunda diagnostics with child-process traversal
- strategy-driven incident retry policy for single-order and bulk-order retry flows
- consultant-managed incident-resolution rule catalog
- BPMN-assisted draft rule suggestions
- vector-backed workflow knowledge retrieval for read-only report enrichment
- deterministic incident-resolution reports after mutation
- post-resolution verification polling and retry observability

## Design Constraints

- Camunda APIs are the source of runtime truth.
- Evidence normalization stays deterministic in Java.
- Read-only diagnostic reports can use the LLM, but only against grounded evidence plus static workflow knowledge.
- Incident-resolution reports are rendered deterministically from Camunda JSON so verified child-process and final-step evidence are not lost.
- Mutation operations remain explicit and retry-intent-gated.

## Main Capabilities

### Diagnostics

- process-instance search by workflow business identifier
- variable inspection
- active flow element inspection
- child subprocess inspection
- grounded markdown reports
- root-instance and process-tree incident visibility

### Incident Resolution

- resolve by incident key
- resolve by process instance
- strategy-driven allow/block/no-action decisions
- child-process incident resolution within the same process tree
- verification checks after mutation before reporting success

### Consultant Rule Management

- persisted `incident_resolution_rule` catalog
- CRUD admin API
- consultant-facing rule editor UI
- BPMN upload assistant for draft suggestions
- rule changes reflected in workflow knowledge retrieval

## Core Components

- [LlamaToolsTestController](src/main/java/com/shubham/dev/bpm_agent/chat/LlamaToolsTestController.java)
  - thin HTTP adapter for chat requests
- [CamundaAgentChatService](src/main/java/com/shubham/dev/bpm_agent/chat/service/CamundaAgentChatService.java)
  - main orchestration service
- [CamundaDiagnosticTools](src/main/java/com/shubham/dev/bpm_agent/chat/CamundaDiagnosticTools.java)
  - Camunda-backed diagnostic and mutation tool surface
- [CamundaToolDispatchService](src/main/java/com/shubham/dev/bpm_agent/chat/service/CamundaToolDispatchService.java)
  - tool extraction and dispatch
- [CamundaEvidenceDigestService](src/main/java/com/shubham/dev/bpm_agent/chat/service/CamundaEvidenceDigestService.java)
  - deterministic evidence normalization
- [CamundaDiagnosticReportService](src/main/java/com/shubham/dev/bpm_agent/chat/service/CamundaDiagnosticReportService.java)
  - LLM-backed read-only reporting plus deterministic mutation reporting
- [CamundaReportGroundingValidator](src/main/java/com/shubham/dev/bpm_agent/chat/validation/CamundaReportGroundingValidator.java)
  - report grounding enforcement
- [WorkflowContextStrategy](src/main/java/com/shubham/dev/bpm_agent/strategy/WorkflowContextStrategy.java)
  - workflow extension contract
- [OrderWorkflowStrategy](src/main/java/com/shubham/dev/bpm_agent/strategy/OrderWorkflowStrategy.java)
  - current workflow strategy implementation
- [WorkflowKnowledgeVectorStoreService](src/main/java/com/shubham/dev/bpm_agent/strategy/retrieval/WorkflowKnowledgeVectorStoreService.java)
  - vector-backed workflow knowledge indexing and retrieval

Detailed architecture:
- [camunda-chat-agent-architecture.md](docs/camunda-chat-agent-architecture.md)
- [camunda-agent-context-framework.md](docs/camunda-agent-context-framework.md)

## Local URLs

- Chat endpoint: `POST /api/llama/chat`
- Consultant rule editor: `http://localhost:8081/admin/incident-rules/index.html`
- H2 console: `http://localhost:8081/h2-console`

## Run Locally

### Requirements

- Java `21`
- Maven or Maven wrapper
- local Camunda 8.9 environment
- local Ollama-compatible runtime
- embedding model available locally, default `nomic-embed-text`

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

Focused examples:

```powershell
mvn -q "-Dtest=CamundaDiagnosticToolsTest,CamundaEvidenceDigestServiceTest,CamundaDiagnosticReportServiceTest" test
```

```powershell
mvn -q "-Dtest=CamundaAgentChatServiceTest,IncidentResolutionRuleCatalogServiceTest,WorkflowKnowledgeVectorStoreServiceTest" test
```

## API Shape

Primary chat endpoint:

- `POST /api/llama/chat`

Example prompts:

```json
{ "prompt": "check for this order id ORD-55422" }
```

```json
{ "prompt": "check for this order id ORD-55422 and retry incident" }
```

```json
{ "prompt": "retry incidents for ORD-55421 and ORD-55422" }
```

## Configuration Notes

Important local configuration lives in [application.yaml](src/main/resources/application.yaml):

- file-backed H2 datasource for rule persistence
- Ollama chat model configuration
- Ollama embedding model configuration
- vector retrieval toggle and top-K setting
- Camunda incident verification polling
- local mock service failure toggles

## Important Behavior

- The agent must not invent runtime state.
- Root-process incident count and full process-tree incident count are not the same thing; reports distinguish them when child subprocesses hold the active incident.
- Read-only diagnostics may use retrieved BPMN and rule context, but live status, incidents, variables, and keys must still come from Camunda.
- Mutation command acceptance is not treated as success unless post-check verification confirms the outcome.
- If the consultant-managed rule catalog is empty, `OrderWorkflowStrategy` falls back to in-code defaults.

## Current Workflow Support

Current first-class strategy:

- `handleOrderId`

Current order workflow support includes:

- `ORD-...` identifier extraction
- category translation
- subprocess-aware incident rule matching
- transient `500` retry handling
- blocking of deployment mismatch and payment `400` incidents

## Documentation

- Architecture:
  - [camunda-chat-agent-architecture.md](docs/camunda-chat-agent-architecture.md)
- Context framework:
  - [camunda-agent-context-framework.md](docs/camunda-agent-context-framework.md)
- Release notes:
  - [release-notes-0.1.0.md](docs/releases/release-notes-0.1.0.md)
- Strategy plan:
  - [strategy-driven-incident-resolution-plan.md](docs/plans/strategy-driven-incident-resolution-plan.md)
- Agent implementation guide:
  - [AGENTS.md](AGENTS.md)

## Current Limits

- `searchProcessInstances` still searches by variable and does not yet enforce workflow `processId` at the tool level.
- bulk retry is currently order-workflow-specific, not generic across all workflows.
- the consultant UI is intentionally minimal and served as a static page without a dedicated frontend build pipeline.

## Next Direction

The next major step is to generalize the current order-specific retrieval and retry-policy model so new workflows can contribute:

- workflow-specific business identifier extraction
- workflow-specific BPMN knowledge indexing
- workflow-specific batch retry behavior
- workflow-specific report interpretation guidance
