# Vector Store Usage Implementation Plan

## Objective

Turn vector-store usage into a first-class framework capability for read-only workflow explanation without letting retrieval replace live Camunda runtime truth.

This plan exists because the current codebase has partial vector-store support, but the implementation is still mixed between:

- production fallback retrieval from strategy + BPMN files
- a test-oriented vector-store constructor
- admin-triggered `refreshIndex()` calls
- documentation that describes a cleaner retrieval model than the current runtime wiring fully provides

The goal is to close that gap.

---

## Non-Goals

- Do not use the vector store as the source of runtime truth.
- Do not use retrieval to determine active incidents, process state, variables, or post-retry success.
- Do not let retrieval mutate workflow state or retry policy directly.
- Do not move deterministic evidence normalization into the LLM.

---

## Target Outcome

After implementation:

1. Read-only reporting uses a consistent retrieval pipeline.
2. Workflow knowledge is indexed through a single production-ready path.
3. Consultant-managed rule changes refresh the retrieval corpus deterministically.
4. BPMN artifacts, strategy context, and persisted rules can all contribute retrievable knowledge.
5. Mutation reports remain deterministic and do not depend on the vector store.

---

## Current State Summary

### What already exists

- `WorkflowKnowledgeVectorStoreService`
- `WorkflowKnowledgeVectorStoreConfig`
- Spring AI vector-store dependency
- retrieval-related config in `application.yaml`
- report-service retrieval hook for read-only diagnostics
- `refreshIndex()` contract used by admin rule management

### Current gaps

- production wiring and test wiring use different mental models
- the service currently has dual constructor paths
- retrieval is only partially corpus-driven
- BPMN indexing is still shallow
- index lifecycle and startup refresh behavior are not yet explicit framework behavior
- architecture docs overstate current cleanliness

---

## Design Principles

- Retrieval is explanatory, not authoritative.
- Retrieval must be workflow-scoped wherever possible.
- Corpus building must be deterministic.
- Admin CRUD should refresh the corpus without side effects on runtime state.
- The orchestration loop must never depend on retrieval to decide whether a mutation succeeded.
- The framework should support new workflows by adding strategy-owned knowledge contributors, not by rewriting the retrieval service for each workflow.

---

## Proposed Retrieval Model

The vector-store corpus should be made of three document classes:

### 1. Workflow Strategy Documents

Source:

- `WorkflowContextStrategy`

Contents:

- process definition ID
- business identifier guidance
- BPMN interpretation guidance
- report-structuring guidance
- strategy-owned retry guidance summaries

Purpose:

- provide stable workflow semantics for read-only explanation

### 2. BPMN Knowledge Documents

Source:

- BPMN XML files in `src/main/resources/bpmns`

Contents:

- process IDs
- process names
- service task names
- call activity relationships
- boundary events
- relevant element summaries

Purpose:

- help the model explain likely routing and subprocess meaning

### 3. Consultant Rule Documents

Source:

- persisted `INCIDENT_RESOLUTION_RULE` rows

Contents:

- workflow scope
- normalized match conditions
- resolution mode
- reason
- user-facing guidance

Purpose:

- allow read-only reports to explain policy context consistently

---

## Recommended Implementation Phases

## Phase 1: Normalize the Service Contract

### Goal

Make `WorkflowKnowledgeVectorStoreService` a clean production service with one primary runtime model.

### Tasks

- remove ambiguity between fallback and vector-backed constructor paths
- keep testability, but move test-specific assembly to test configuration where possible
- make the production constructor own:
  - `VectorStore`
  - registered `WorkflowContextStrategy` list
  - `IncidentResolutionRuleRepository`
  - retrieval config
- keep `IncidentResolutionRuleCatalogService` out of the indexing path if the repository is already the source for persisted rule corpus building

### Expected result

- one coherent service contract
- clearer Spring bean creation
- cleaner architecture alignment

---

## Phase 2: Make Index Lifecycle Explicit

### Goal

Treat index build/refresh as a framework lifecycle concern.

### Tasks

- add startup initialization that calls `refreshIndex()`
- document whether refresh is:
  - full rebuild
  - append-only
  - replace-and-rebuild
- make refresh behavior deterministic and idempotent
- ensure admin CRUD calls only trigger knowledge refresh, not runtime state changes

### Expected result

- no hidden retrieval behavior
- predictable corpus freshness after startup and rule changes

---

## Phase 3: Improve BPMN Corpus Construction

### Goal

Make BPMN retrieval useful beyond filename previews.

### Tasks

- parse BPMN XML into structured text documents
- include:
  - process ID
  - process name
  - service tasks
  - call activities
  - gateways
  - boundary events
- chunk large BPMN content into retrievable sections
- attach metadata such as:
  - `workflowProcessDefinitionId`
  - `knowledgeType=bpmn`
  - `sourceFile`
  - `elementType`

### Expected result

- retrieval becomes meaningfully BPMN-aware
- read-only reports can explain routing with better context

---

## Phase 4: Improve Rule Corpus Construction

### Goal

Make consultant-managed rule retrieval more useful and explainable.

### Tasks

- build one retrieval document per persisted rule
- include normalized summaries of:
  - error types
  - HTTP statuses
  - message tokens
  - resolution mode
  - reason
  - guidance
- attach metadata:
  - `workflowProcessDefinitionId`
  - `knowledgeType=incident-rule`
  - `ruleId`
  - `resolutionMode`

### Expected result

- policy explanation in read-only reports becomes more coherent
- retrieval remains deterministic and auditable

---

## Phase 5: Strategy-Owned Retrieval Contribution

### Goal

Make the framework extensible for future workflows.

### Tasks

- add optional strategy methods such as:
  - `generateRetrievalDocuments()`
  - or `generateRetrievalSeedText()`
- allow each strategy to contribute workflow-specific knowledge cleanly
- keep `OrderWorkflowStrategy` as the reference implementation

### Expected result

- new workflows can participate in retrieval without editing generic service logic

---

## Phase 6: Reporting Integration Cleanup

### Goal

Make retrieval usage explicit and bounded in reporting.

### Tasks

- ensure only read-only diagnostic flows call retrieval-backed report generation
- ensure incident-resolution/mutation reports bypass retrieval and remain deterministic
- make the report prompt distinguish:
  - `CAMUNDA EVIDENCE`
  - `RETRIEVED WORKFLOW KNOWLEDGE`
- explicitly instruct the model that retrieved knowledge is interpretive context only

### Expected result

- clearer trust boundaries
- lower risk of the model blending static knowledge with live runtime claims

---

## Phase 7: Test Coverage Expansion

### Goal

Make vector-store behavior safe to evolve.

### Tasks

- add/update tests for:
  - startup refresh behavior
  - admin CRUD refresh behavior
  - workflow-scoped retrieval filtering
  - BPMN document ingestion
  - rule document ingestion
  - read-only report retrieval usage
  - mutation-report retrieval bypass

### Suggested tests

- `WorkflowKnowledgeVectorStoreServiceTest`
- `CamundaDiagnosticReportServiceTest`
- `IncidentResolutionRuleCatalogServiceTest`

### Expected result

- retrieval can evolve without reintroducing drift between source, tests, and docs

---

## Recommended Class-Level Changes

### Existing classes to refine

- `src/main/java/com/shubham/dev/bpm_agent/strategy/retrieval/WorkflowKnowledgeVectorStoreService.java`
- `src/main/java/com/shubham/dev/bpm_agent/strategy/retrieval/WorkflowKnowledgeVectorStoreConfig.java`
- `src/main/java/com/shubham/dev/bpm_agent/chat/service/CamundaDiagnosticReportService.java`
- `src/main/java/com/shubham/dev/bpm_agent/strategy/persistence/IncidentResolutionRuleManagementService.java`
- `src/main/java/com/shubham/dev/bpm_agent/strategy/WorkflowContextStrategy.java`

### New classes worth introducing

- `WorkflowKnowledgeDocumentFactory`
- `BpmnKnowledgeExtractor`
- `RuleKnowledgeDocumentFactory`
- `WorkflowKnowledgeIndexRefresher`

These should be introduced only if the current retrieval service starts carrying too many responsibilities.

---

## Loop Engineering Considerations

Vector-store usage must respect the orchestration loop rules.

### Retrieval should happen:

- after workflow resolution
- before final read-only explanation
- never as a substitute for tool execution

### Retrieval should not happen:

- as a repeated fallback inside the tool loop
- after a mutation as a way to infer success
- in place of post-resolution verification

### Loop rule

For read-only flows:

1. resolve workflow
2. gather Camunda evidence
3. normalize evidence
4. retrieve workflow knowledge
5. generate explanation
6. validate grounding

For mutation flows:

1. resolve workflow
2. gather pre-mutation evidence
3. evaluate policy
4. mutate if allowed
5. verify post-mutation state
6. render deterministic report

Retrieval should not be inserted between mutation and verification.

---

## Documentation Changes Required When Implemented

Update together:

- `docs/camunda-chat-agent-architecture.md`
- `docs/camunda-agent-context-framework.md`
- `README.md`
- `AGENTS.md` if loop or framework-template rules change materially

---

## Recommended Delivery Order

1. Phase 1: normalize service contract
2. Phase 2: explicit index lifecycle
3. Phase 4: rule corpus cleanup
4. Phase 3: BPMN corpus improvement
5. Phase 6: reporting integration cleanup
6. Phase 5: strategy-owned retrieval contribution
7. Phase 7: expanded tests

This order reduces drift first, then improves corpus quality.

---

## Success Criteria

The vector-store implementation is in good shape when:

- production wiring uses one coherent retrieval model
- startup and rule CRUD refresh the index deterministically
- BPMN and rule knowledge are both retrievable
- read-only reports can explain workflows better with retrieval context
- mutation reports remain deterministic
- a new workflow can plug into retrieval through strategy-level extension

---

## Final Recommendation

Do not treat vector-store usage as “RAG because we have an LLM”.

Treat it as a framework capability with a narrow purpose:

> improve workflow explanation without weakening runtime truth boundaries

That framing keeps retrieval useful, testable, and safe.
