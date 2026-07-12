# Building a Camunda Incident Agent as a Reusable Framework Template

*How to combine Camunda, Spring Boot, grounded reporting, consultant-managed retry policy, and LLM assistance without letting the model become the source of truth*

## Draft Subtitle

From workflow-specific incident handling to a strategy-driven framework template for AI-assisted workflow operations.

## Suggested Tags

`Camunda` `Spring Boot` `Java` `AI Agents` `LLM` `Workflow Automation`

---

A lot of AI agent demos look good until they touch production-style workflow operations.

The moment an agent is allowed to inspect incidents, retry failures, or explain process state, the bar changes:

- it cannot invent runtime facts
- it cannot confuse a mutation command with a successful outcome
- it cannot stay hardcoded to one workflow if the platform is meant to grow

That was the problem I wanted to solve.

I started with a Camunda-focused incident assistant for an order workflow. Over time, it became obvious that this should not remain a one-off project. It needed to become a reusable framework template for AI-assisted workflow operations.

This article covers the design choices that made that shift possible:

- strategy-driven workflow behavior
- deterministic evidence normalization
- safe incident retry
- consultant-managed resolution rules
- BPMN-assisted rule authoring
- vector-backed reporting context
- explicit loop engineering

The most important principle is simple:

> The LLM can help interpret the system, but Camunda must remain the source of truth.

---

## The Real Problem Behind Workflow Agents

Consider a prompt like:

> “Check order `ORD-55448` and retry the incident if possible.”

That single request hides several operational questions:

1. Which workflow does this belong to?
2. What is the real business identifier?
3. Which process instance is active?
4. Is the incident on the root process or a child subprocess?
5. Should this incident actually be retried?
6. If a retry is attempted, did the workflow really recover?
7. What should the final report say?

If the agent loop improvises all of that with prompting alone, it becomes fragile very quickly.

So I split responsibilities aggressively instead.

---

## The Architecture

The framework ended up with six clear responsibilities.

### 1. Orchestration

`CamundaAgentChatService` coordinates the session:

- workflow strategy resolution
- tool/model loop execution
- retry-intent gating
- deterministic short-circuiting
- policy evaluation before mutation
- final report routing

This is the session coordinator, not the owner of domain truth.

### 2. Tool Surface

`CamundaDiagnosticTools` exposes a small, explicit Camunda-facing toolset:

- search process instances
- fetch variables
- diagnose a process instance
- resolve an incident by key
- resolve incidents by process instance

That surface is intentionally narrow. Operational systems get less safe when the tool layer becomes fuzzy.

### 3. Workflow Strategy

`WorkflowContextStrategy` owns workflow-specific behavior.

For the current order workflow, `OrderWorkflowStrategy` handles:

- prompt applicability
- business identifier extraction
- BPMN context
- variable translation
- reporting guidance
- retry policy

That was the first real step from “demo” to “framework”.

### 4. Evidence Normalization

`CamundaEvidenceDigestService` converts raw Camunda payloads into canonical evidence.

This layer is deterministic on purpose:

- it normalizes structure
- it extracts stable facts
- it distinguishes root incidents from process-tree incidents
- it does **not** infer success from an accepted mutation command

That boundary matters a lot once an LLM is involved.

### 5. Reporting

`CamundaDiagnosticReportService` handles user-facing reports.

The important split is:

- read-only diagnostic reports may use the LLM, but under a grounding contract
- mutation reports are rendered deterministically from verified Camunda payloads

This became necessary after seeing the LLM occasionally truncate process-instance keys or describe contradictory states during retry flows.

### 6. Consultant-Managed Policy

Retry policy is no longer buried only in Java.

The framework now includes:

- H2 + Flyway persistence
- a rule catalog
- an admin API
- a lightweight consultant UI
- BPMN upload for draft rule suggestions

That means retry behavior can be managed safely without editing code for every rule change.

---

## Why Strategy-Driven Design Matters

One common failure mode in agent systems is pretending workflow-specific behavior is generic.

For example:

- `ORD-...` is not a universal identifier format
- `orderId` is not a universal lookup field
- order retry policy is not framework-wide retry policy

So instead of letting generic orchestration absorb more and more order-specific logic, I pushed that behavior into workflow strategies.

That creates a clean path for future workflows, such as a loan-processing flow with:

- `loanId` instead of `orderId`
- a different subprocess layout
- different retry rules
- different report semantics

The framework goal is:

> Add a new workflow by implementing a strategy, not by rewriting orchestration.

---

## The Incident-Retry Safety Model

Retry flows need more control than read-only flows.

The lifecycle now works like this:

### Step 1. Resolve workflow and identifier

The selected strategy determines how to interpret the business identifier.

### Step 2. Search Camunda

The tool layer isolates the active process instance.

### Step 3. Diagnose before mutation

Before retrying anything, the framework gathers:

- process state
- active incidents
- variables
- flow elements
- child subprocess diagnostics

### Step 4. Evaluate policy

The strategy builds an `IncidentResolutionDecision`.

Examples:

- allow retry for transient HTTP `500` failures
- block retry for payment HTTP `400` failures
- block retry for called-process deployment mismatches
- return `NO_ACTION` when no active incident exists

### Step 5. Mutate only when allowed

If the policy allows it, the framework retries by:

- incident key
- or process instance

depending on policy mode.

### Step 6. Verify after mutation

This is the critical part.

A Camunda mutation command being accepted is **not** enough to call the incident resolved.

The framework verifies again and checks:

- whether incidents really cleared
- whether the token moved
- whether downstream subprocesses progressed

### Step 7. Report from the last verified state

This turned out to be one of the most important lessons.

If the report uses the old incident-era snapshot, it can miss later subprocesses that only appear after the retry succeeds. So mutation reporting must use the latest verified evidence, not the pre-retry view.

---

## What the LLM Should and Should Not Do

I did not remove the LLM from the system. I narrowed its responsibility.

### Where the LLM helps

- prompt interpretation
- tool routing
- read-only explanation
- BPMN-assisted draft suggestions
- workflow knowledge retrieval

### Where the LLM should not be trusted blindly

- exact instance keys
- exact child-process listings
- exact post-mutation runtime state
- incident clearance claims
- operational success assertions

That led to a simple rule:

> Use the LLM for interpretation, not for runtime truth.

This is the difference between useful AI-assisted workflow tooling and a system that only sounds intelligent.

---

## Consultant-Managed Rules and BPMN Upload

One of the most useful changes was moving retry policy out of hardcoded Java-only logic.

A rule can now express things like:

- match `JOB_NO_RETRIES`
- match HTTP `500`
- match messages containing `inventory-reservation`
- use resolution mode `BY_PROCESS_INSTANCE`

Or:

- match HTTP `400`
- use resolution mode `BLOCKED`
- provide user-facing guidance explaining why retry is unsafe

To make authoring easier, I also added a BPMN-assisted draft flow:

1. Upload parent BPMN and subprocess BPMNs
2. Parse likely integration points
3. Suggest incident-rule candidates
4. Let the consultant review and save explicitly

Nothing is auto-persisted from the model suggestion path. That was a deliberate safety decision.

---

## Why I Added a Vector Store

As reporting got richer, I wanted better context without letting retrieval replace runtime truth.

So I added a Spring AI VectorStore layer for workflow knowledge.

Its job is to retrieve:

- BPMN context
- consultant-managed retry rules
- workflow interpretation hints

Its job is **not** to decide:

- whether an incident cleared
- what variables are active now
- which subprocess is currently running

That split is important:

- Camunda tells us what happened
- retrieval helps explain it

---

## Loop Engineering Matters More Than People Admit

If there is one under-discussed topic in agent systems, it is loop engineering.

People focus on prompts, models, and tools. But in operational systems, the loop is where reliability is won or lost.

The framework now treats loops with explicit invariants:

- bounded iteration count
- duplicate-tool-call protection
- mutation gating before side effects
- deterministic short-circuit exits
- post-mutation verification before success
- final reporting from the latest verified state

Without those controls, agents tend to:

- repeat the same tool call
- retry mutations too early
- report from stale evidence
- mix diagnosis and mutation reasoning in unstable ways

The main lesson is:

> A production-friendly agent needs engineered control flow, not just a better prompt.

---

## Why I Now Think of This as a Framework Template

At this point, I no longer think of the project as “an order incident assistant”.

I think of it as:

> a reusable framework template for grounded, strategy-driven workflow operations on top of Camunda

That template gives a clean extension model:

### Add a workflow by contributing:

- a `WorkflowContextStrategy`
- business identifier extraction
- BPMN context
- report instructions
- retry policy

### Reuse the existing framework for:

- Camunda diagnostics
- evidence normalization
- mutation gating
- verification
- reporting
- rule administration
- retrieval-backed explanation

That is the pattern I would recommend for teams building operational agents around workflow engines.

---

## Closing Thought

The most valuable thing I learned from this project is that AI workflow tooling becomes much more useful once you stop treating the model as the owner of the system.

The LLM is powerful, but it should live inside a framework that already knows:

- where truth comes from
- who owns policy
- when mutation is allowed
- how verification works
- how reporting is grounded

That is what turns an agent from a demo into operational software.

If you are building something similar on Camunda, Spring Boot, or another workflow engine, I would strongly recommend designing those boundaries early.

It is much easier to add intelligent explanation to a deterministic core than to recover deterministic behavior from an over-permissive agent later.

---

## Suggested Final Title Options

1. **Building a Camunda Incident Agent as a Reusable Framework Template**
2. **From Workflow Demo to Framework: Designing a Grounded Camunda AI Agent**
3. **How I Built a Strategy-Driven Camunda Incident Agent with Spring Boot**
4. **LLMs, Camunda, and Incident Retry: Building a Safer Workflow Agent**
5. **Designing an AI-Assisted Camunda Operations Framework Without Losing Runtime Truth**
