# Workflow Agent Implementation Guide

## Project Snapshot
- Stack: Spring Boot `3.5.15-SNAPSHOT`, Java `21`, Maven, JUnit 5, Spring AI, Camunda 8 REST integration.
- Base package is `com.shubham.dev.bpm_agent`.
- This is no longer a scaffold. The repo contains:
  - Camunda diagnostic and incident-resolution tools
  - an LLM-driven chat orchestration flow
  - workflow strategy resolution
  - Camunda worker registration and workflow workers
  - evidence normalization and grounding validation

## Package and Naming Rules
- Use `com.shubham.dev.bpm_agent` as the root package for all new Java classes.
- Do not introduce `bpm_ai`, `bpm-ai`, or any other package variant.
- Keep names aligned with current structure:
  - `chat` for controller/tool surface
  - `chat.service` for orchestration/reporting/evidence services
  - `chat.validation` for grounding checks
  - `chat.model` for normalized evidence records
  - `camunda` for REST client and Camunda-facing infrastructure
  - `camunda.workers` for worker registration and worker implementations
  - `strategy` for workflow-specific context resolution

## Current Entry Points
- Application entry point:
  - `src/main/java/com/shubham/dev/bpm_agent/BpmAiApplication.java`
- Chat HTTP adapter:
  - `src/main/java/com/shubham/dev/bpm_agent/chat/LlamaToolsTestController.java`
- Main orchestration service:
  - `src/main/java/com/shubham/dev/bpm_agent/chat/service/CamundaAgentChatService.java`

## Current Architecture

### Web Layer
- `LlamaToolsTestController`
  - thin HTTP adapter
  - delegates to application services
  - exposes chat and deterministic incident-resolution endpoints

### Application / Orchestration Layer
- `CamundaAgentChatService`
  - resolves workflow strategy from prompt
  - gates mutation tools behind explicit retry intent
  - runs the model/tool loop
  - routes diagnosis and retry payloads into final reporting

### Tool Layer
- `CamundaDiagnosticTools`
  - Spring AI tool surface over Camunda REST APIs
  - exposes:
    - `searchProcessInstances`
    - `fetchVariablesForInstance`
    - `diagnoseProcessInstance`
    - `resolveIncidentByKey`
    - `resolveIncidentsByProcessInstance`

- `CamundaToolDispatchService`
  - extracts tool JSON from model output
  - dispatches tool calls
  - blocks mutation tools unless explicitly allowed

### Reporting / Grounding Layer
- `CamundaDiagnosticReportService`
  - builds final user-facing markdown using a report-only model client

- `CamundaEvidenceDigestService`
  - deterministically converts Camunda payloads into canonical evidence
  - must normalize structure only
  - must not infer runtime process state from mutation results

- `CamundaReportGroundingValidator`
  - rejects invented IDs, process names, state contradictions, incident contradictions, and leaked tool JSON

### Workflow Strategy Layer
- `WorkflowContextStrategy`
  - contract for workflow-specific process resolution and context

- `WorkflowStrategyRegistry`
  - selects matching workflow strategy

- `OrderWorkflowStrategy`
  - current concrete strategy for `handleOrderId`

### Camunda Integration Layer
- `CamundaOrchestrationClient`
  - encapsulates Camunda REST v2 calls
  - used for search, diagnosis, and incident-resolution mutations

### Worker Layer
- `AgentWorkerFactory`
  - registers job workers
- `HandleOrderWorkers`
  - order workflow workers
- `SubProcessWorkers`
  - subprocess-specific workers

## Operational Rules for Agents Working in This Repo
- Do not add fabricated business data to any agent response.
- Do not shift evidence normalization into the LLM; keep it deterministic in Java.
- Treat mutation operations as explicit operational actions, not passive reads.
- Do not treat Camunda mutation command acceptance as business success unless post-check evidence confirms the incident cleared.
- Prefer process-instance incident resolution when the workflow instance is known and a specific incident key may be stale.
- Keep workflow-specific identifier normalization in `WorkflowContextStrategy` implementations, not in generic orchestration code.

## Current Workflow-Specific Notes
- Current primary workflow strategy is `handleOrderId`.
- `orderId` is the primary business identifier for the order flow.
- Current strategy translations:
  - `advanced` -> `category=2`
  - `premier` / `high priority` -> `category=1`
  - `regular` -> `category=3`
- Important BPMN caveat:
  - the regular-track called process IDs must stay aligned between the parent BPMN and deployed child BPMNs
  - missing called-process deployments are a known source of incidents

## Build, Test, and Run
- Prefer Maven commands already used in the repo:
  - `mvn -q -DskipTests compile`
  - `mvn -q test`
  - focused examples:
    - `mvn -q "-Dtest=CamundaAgentChatServiceTest,CamundaDiagnosticToolsTest" test`
- If you specifically want the Maven wrapper, keep Windows-friendly examples:
  - `.\mvnw.cmd test -DskipTests=false`
  - `.\mvnw.cmd spring-boot:run`

## Testing Guidance
- Baseline integration test:
  - `src/test/java/com/shubham/dev/bpm_agent/BpmAiApplicationTests.java`
- Current focused tests include:
  - `src/test/java/com/shubham/dev/bpm_agent/chat/CamundaDiagnosticToolsTest.java`
  - `src/test/java/com/shubham/dev/bpm_agent/chat/service/CamundaAgentChatServiceTest.java`
  - `src/test/java/com/shubham/dev/bpm_agent/chat/service/CamundaEvidenceDigestServiceTest.java`
  - `src/test/java/com/shubham/dev/bpm_agent/chat/service/CamundaToolDispatchServiceTest.java`
  - `src/test/java/com/shubham/dev/bpm_agent/chat/validation/CamundaReportGroundingValidatorTest.java`
- When changing:
  - routing logic -> add/update `CamundaAgentChatServiceTest`
  - evidence semantics -> add/update `CamundaEvidenceDigestServiceTest`
  - mutation result handling -> add/update `CamundaDiagnosticToolsTest`
  - grounding rules -> add/update `CamundaReportGroundingValidatorTest`

## Documentation to Keep in Sync
- `docs/camunda-chat-agent-architecture.md`
- `docs/camunda-agent-context-framework.md`

When changing tool names, retry semantics, workflow strategy behavior, evidence normalization, or package structure, update these docs in the same change.

## Project-Specific Conventions to Preserve
- Java release is `21`; do not introduce newer language/runtime requirements.
- Parent and plugin versions are `3.5.15-SNAPSHOT`; snapshot repository requirements in `pom.xml` must remain valid.
- Keep instructions and examples Windows-friendly first.
- Keep changes focused; do not rewrite unrelated workflow logic or BPMN context opportunistically.

## Practical Checklist Before Submitting Changes
- Confirm all new classes are under `com.shubham.dev.bpm_agent`.
- Confirm workflow-specific behavior is implemented in strategies, not hardcoded in generic orchestration.
- Confirm mutation tools remain explicit and gated.
- Run the most relevant focused tests for the touched area.
- Update architecture/context docs if behavior or contracts changed.
