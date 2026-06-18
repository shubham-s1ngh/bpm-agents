# Camunda Agent Context Framework

## Objective

Build a workflow-context framework that lets an operational agent diagnose Camunda 8 process instances without hallucinating workflow identity, business identifiers, runtime state, incidents, or remediation details.

The framework must support many workflows where variables may overlap across processes. The agent must therefore resolve the Camunda `processId` first, then resolve the workflow-specific business identifier and only then query Camunda orchestration APIs for runtime evidence.

Current local target:

- Camunda: local Camunda 8.9 setup
- Application: Spring Boot service on `server.port=8081`
- Camunda REST base: `http://localhost:8080/v2`
- LLM runtime: local Ollama-compatible model configured through `application.yaml`

## Non-Negotiable Agent Rules

- The agent must identify the workflow `processId` before searching process instances.
- The agent must not infer runtime facts that are not returned by Camunda APIs.
- The agent must not add custom business data, variable values, statuses, logs, or error messages.
- The agent may use BPMN strategy context to interpret paths, activities, variable meaning, and pending work.
- The final diagnosis must clearly separate API evidence from business interpretation.
- Mutation tools such as incident resolution must be treated as explicit operational actions, not diagnostic reads.

## Current Implementation Map

| Area | File | Responsibility |
| --- | --- | --- |
| HTTP adapter | `src/main/java/com/shubham/dev/bpm_agent/chat/LlamaToolsTestController.java` | Thin controller that accepts `/api/llama/chat` and delegates chat and mutation requests to the application service. |
| Session orchestrator | `src/main/java/com/shubham/dev/bpm_agent/chat/service/CamundaAgentChatService.java` | Resolves workflow strategy, gates mutation intent, runs the tool loop, and routes diagnosis or retry results into the reporting path. |
| Tool dispatch | `src/main/java/com/shubham/dev/bpm_agent/chat/service/CamundaToolDispatchService.java` | Extracts tool JSON from model output, dispatches allowed tools, and serializes tool results. |
| Diagnostic tools | `src/main/java/com/shubham/dev/bpm_agent/chat/CamundaDiagnosticTools.java` | Exposes Camunda search, variable fetch, deep diagnosis, and incident-resolution tools with post-resolution verification. |
| Report service | `src/main/java/com/shubham/dev/bpm_agent/chat/service/CamundaDiagnosticReportService.java` | Converts tool payloads into grounded markdown through canonical evidence digests and report validation. |
| Evidence digest | `src/main/java/com/shubham/dev/bpm_agent/chat/service/CamundaEvidenceDigestService.java` | Deterministically normalizes diagnostic and incident-resolution JSON into canonical evidence. |
| Grounding validator | `src/main/java/com/shubham/dev/bpm_agent/chat/validation/CamundaReportGroundingValidator.java` | Rejects unsupported identifiers, contradictory states, incorrect incident summaries, and leaked tool JSON. |
| Camunda REST client | `src/main/java/com/shubham/dev/bpm_agent/camunda/CamundaOrchestrationClient.java` | Calls Camunda 8 REST v2 endpoints using Spring `RestClient` for search, diagnosis, and mutation operations. |
| Workflow strategy contract | `src/main/java/com/shubham/dev/bpm_agent/strategy/WorkflowContextStrategy.java` | Defines process ID resolution, prompt applicability, context instructions, variable translation, and report instructions. |
| Strategy registry | `src/main/java/com/shubham/dev/bpm_agent/strategy/WorkflowStrategyRegistry.java` | Selects the first applicable workflow strategy for a user prompt. |
| Current strategy | `src/main/java/com/shubham/dev/bpm_agent/strategy/OrderWorkflowStrategy.java` | Provides context for the `handleOrderId` workflow. |

## Current Diagnostic Tools

### `searchProcessInstances`

Purpose:

- Queries Camunda process instances by one process variable name/value pair.

Current inputs:

- `variableName`
- `variableValue`

Current Camunda endpoint:

- `POST /process-instances/search`

Current limitation:

- This tool does not currently accept or enforce `processId`.
- In a multi-workflow environment, searching only by variable can return instances from unrelated workflows if they share variable names or values.

Framework requirement:

- Add `processId` or `processDefinitionId` as a required filter before this is used for production diagnosis.

### `fetchVariablesForInstance`

Purpose:

- Fetches variables for a known `processInstanceKey`.

Current input:

- `processInstanceKey`

Current Camunda endpoint:

- `POST /variables/search`

Usage boundary:

- This should be used only after the target instance is selected from Camunda API results.

### `diagnoseProcessInstance`

Purpose:

- Compiles diagnostic telemetry for a known `processInstanceKey`.

Current collected data:

- Active incidents from `/incidents/search`
- Runtime variables from `/variables/search`
- Element instances from `/element-instances/search`

Returned shape:

- `processInstanceKey`
- `activeIncidents`
- `runtimeVariables`
- `activeSteps`
- `status`
- `error`, only when the diagnostic call fails

Usage boundary:

- This is the main evidence payload for final agent answers.
- The agent must quote or summarize only values found in this payload when reporting runtime state.

### `resolveIncidentByKey`

Purpose:

- Resolves one specific active incident by incident key.

Current endpoint:

- `POST /incidents/{incidentKey}/resolution`

Usage boundary:

- This is a mutation tool.
- It should not be called unless the user explicitly asks to retry or confirms a proposed remediation action.
- The tool now verifies post-resolution state and reports success only when the incident is no longer active.

### `resolveIncidentsByProcessInstance`

Purpose:

- Resolves the current active incidents for a known `processInstanceKey`.

Current endpoint:

- `POST /process-instances/{processInstanceKey}/incident-resolution`

Usage boundary:

- This is a mutation tool.
- It is the safer choice when the agent knows the process instance but not a fresh current incident key.
- The tool now re-queries incidents after resolution and returns failure if active incidents still remain.

## Required Agent Resolution Flow

The target production flow should be:

1. Resolve workflow intent from the user prompt.
2. Select exactly one `WorkflowContextStrategy`.
3. Extract the strategy `processId`.
4. Extract the workflow-specific business identifier from the prompt.
5. Normalize identifier aliases using strategy context.
6. Search Camunda for process instances using both `processId` and business identifier.
7. Select the relevant instance from Camunda API evidence.
8. If the user explicitly asked to retry or resolve incidents, call the process-instance incident-resolution path instead of staying in read-only diagnosis.
9. Diagnose the selected instance through incidents, variables, element instances, and child process traversal when the prompt is diagnostic.
10. Analyze completed, waiting, failed, and pending path segments using BPMN strategy context.
11. Return a final report that contains no data beyond Camunda API evidence and static BPMN/strategy context.

If the workflow cannot be resolved to one `processId`, the agent must ask a clarifying question instead of querying by shared variables.

If the business identifier cannot be resolved, the agent must ask for the missing identifier instead of guessing a variable name or value.

## Workflow Strategy Contract

Each workflow should have a strategy class implementing `WorkflowContextStrategy`.

Required strategy content:

- Canonical Camunda `processId`.
- Prompt matching rules for workflow intent.
- Business identifier aliases and canonical variable names.
- Variable translations where business wording maps to Camunda values.
- BPMN context describing major tasks, gateways, call activities, incidents, and expected wait states.
- Report rules for how to interpret and present runtime evidence.

Recommended strategy metadata per workflow:

| Field | Example | Purpose |
| --- | --- | --- |
| `processId` | `handleOrderId` | Prevents cross-workflow variable collisions. |
| `businessIdentifiers` | `order id`, `OrderID`, `order-id` -> `orderId` | Lets the agent normalize user language. |
| `primaryIdentifierVariable` | `orderId` | Defines the variable used for instance lookup. |
| `calledProcesses` | `subProcess_InventorySystem`, `subProcess_PaymentGateway` | Lets the agent reason across call activities. |
| `importantVariables` | `category`, `stockStatus`, `paymentStatus` | Defines variables that affect path interpretation. |
| `pathRules` | `category=1` means advanced track | Maps variable state to BPMN paths. |
| `incidentRules` | Use exact `errorType` and `errorMessage` | Prevents invented error summaries. |

## Current `handleOrderId` Context

Current strategy:

- `OrderWorkflowStrategy`

Canonical process:

- `handleOrderId`

Prompt applicability:

- Contains `order`
- Contains `category`
- Contains `handleorder`
- Contains order workflow wording resolved by strategy matching

Current variable translations:

| User wording | Camunda variable |
| --- | --- |
| `advanced` | `category=2` |
| `premier` | `category=1` |
| `high priority` | `category=1` |
| `regular` | `category=3` |

Known workflow variables from strategy and workers:

| Variable | Meaning |
| --- | --- |
| `category` | Drives downstream path evaluation in the order workflow. |
| `stockStatus` | Set by inventory reservation; values include `IN_STOCK` or `OUT_OF_STOCK`. |
| `paymentStatus` | Set by payment charge when successful. |
| `simulatePayment` | Can force payment worker behavior such as decline simulation. |
| `orderId` | Primary business identifier for order lookup. |

Known job types:

| Job type | Business activity |
| --- | --- |
| `inventory-reservation` | Allocates stock and sets `stockStatus`. |
| `payment-charge` | Charges customer payment and sets `paymentStatus` on success. |
| `warehouse-prep-priority` | Prepares high-priority items. |
| `carrier-dispatch-priority` | Dispatches expedited shipment. |
| `warehouse-pack-standard` | Performs standard warehouse packing. |
| `carrier-dispatch-standard` | Dispatches standard ground shipment. |
| `inventory-rollback` | Rolls back stock inventory. |
| `send-confirmation-email` | Sends dispatch notification. |

Known call activities from `handleOrder.bpmn`:

| Call activity | Called process ID |
| --- | --- |
| `subProcess_InventoryService` | `subProcess_InventorySystem` |
| `CallActivity_Payment` | `subProcess_PaymentGateway` |
| `CallActivity_Advanced` | `advanceCategory_processId` |
| `CallActivity_Regular` | `subProcess_RegularOrderTrack` |
| `CallActivity_Notification` | `subProcess_NotificationSystem` |

Important model gap:

- `handleOrder.bpmn` currently calls `subProcess_RegularOrderTrack`, but incident evidence has also shown missing deployments for `regularCategory_ProcessId1` in runtime scenarios.
- The repository regular-track BPMN IDs must be kept aligned with what the deployed parent process actually calls.
- This mismatch can cause incidents if the called process ID is not deployed under the expected ID.
- The agent must report the exact Camunda incident error if this happens; it must not invent the cause unless the error message and BPMN context support that interpretation.

Important routing fact from BPMN strategy:

- `category=1` currently maps to `path="premier"` in the parent logic.
- The BPMN gateway does not have a dedicated premier branch.
- That means `category=1` currently falls through the default regular track unless the BPMN is corrected.

## Evidence Versus Interpretation

Final reports should use two categories:

### API Evidence

Data returned by Camunda APIs:

- Process instance keys
- Process states
- Mutation operation status
- Variables and values
- Incident keys
- Incident `errorType`
- Incident `errorMessage`
- Element instance states
- Remaining incidents after retry/resolution

### BPMN Interpretation

Static context from strategy and BPMN files:

- Which task or call activity an element represents
- Which path is selected by a variable
- Which downstream steps are expected but not yet completed
- Which sub-process belongs to a larger workflow path

The agent may say an activity is pending only when Camunda element state and BPMN structure support that conclusion.

The agent must not treat mutation command acceptance as business success unless the post-resolution Camunda evidence confirms the incident is gone.

## Target Tooling Improvements

The current code is a useful proof of concept. To support tens of workflows rigorously, these changes should be prioritized:

1. Add `processId` as a required input to `searchProcessInstances`.
2. Add a first-class tool for workflow resolution that returns one strategy or asks for clarification.
3. Add a strategy-defined identifier extractor instead of relying on the LLM to choose variable names.
4. Load BPMN XML metadata by process ID to enrich static context safely.
5. Add child process traversal so called process instances can be diagnosed with the parent instance.
6. Separate read-only diagnostic tools from mutation tools in prompts and controller logic.
7. Return structured diagnosis DTOs instead of raw `Map<String, Object>` where practical.
8. Add strategy-aware `processId` filtering to process search so variable collisions across workflows are prevented at the tool level.
9. Add tests for process resolution, variable alias normalization, mutation routing, and no-hallucination output rules.

## Example Intended User Flow

User prompt:

```text
Can you tell me about this order id ORD-55421 from orders?
```

Expected framework behavior:

1. Resolve workflow as `handleOrderId`.
2. Normalize `order id`, `order-id`, `OrderID`, or similar aliases to canonical variable `orderId`.
3. Search Camunda for `processId=handleOrderId` and `orderId=ORD-55421`.
4. If the prompt asks for retry, resolve incidents for the selected active `processInstanceKey`; otherwise diagnose it.
5. Report only returned incidents, variables, mutation outcome, and active elements.
6. Use `handleOrderId` BPMN context to explain completed path, waiting activity, failed activity, and pending activities.

User prompt:

```text
Can you tell me about this id in procurement workflow?
```

Expected framework behavior:

1. Resolve workflow as the procurement strategy process ID.
2. Use procurement-specific identifier aliases and variable mappings.
3. Query only procurement instances.
4. Avoid reusing order workflow variable assumptions.

## Documentation Maintenance Rules

- Add one strategy section per workflow.
- Keep process IDs exactly aligned with deployed BPMN IDs.
- Document variable aliases separately from Camunda variable names.
- Document call activities and child process IDs explicitly.
- Mark every mutation tool as mutation-only.
- Document whether a reported success means command acceptance only or verified post-check success.
- Do not document imagined runtime behavior unless the BPMN, workers, or Camunda API evidence supports it.
