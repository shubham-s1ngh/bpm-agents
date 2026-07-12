package com.shubham.dev.bpm_agent.strategy.retrieval;

import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionRule;
import com.shubham.dev.bpm_agent.strategy.WorkflowContextStrategy;
import com.shubham.dev.bpm_agent.strategy.persistence.IncidentResolutionRuleEntity;
import com.shubham.dev.bpm_agent.strategy.persistence.IncidentResolutionRuleRepository;
import com.shubham.dev.bpm_agent.strategy.retrieval.BpmnKnowledgeExtractor.BpmnBoundaryEventKnowledge;
import com.shubham.dev.bpm_agent.strategy.retrieval.BpmnKnowledgeExtractor.BpmnCallActivityKnowledge;
import com.shubham.dev.bpm_agent.strategy.retrieval.BpmnKnowledgeExtractor.BpmnElementKnowledge;
import com.shubham.dev.bpm_agent.strategy.retrieval.BpmnKnowledgeExtractor.BpmnProcessKnowledge;
import com.shubham.dev.bpm_agent.strategy.retrieval.BpmnKnowledgeExtractor.BpmnTaskKnowledge;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class WorkflowKnowledgeVectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowKnowledgeVectorStoreService.class);

    private final VectorStore vectorStore;
    private final List<WorkflowContextStrategy> workflowStrategies;
    private final IncidentResolutionRuleRepository ruleRepository;
    private final BpmnKnowledgeExtractor bpmnKnowledgeExtractor;
    private final boolean retrievalEnabled;
    private final int topK;
    private final String storePath;
    private final AtomicBoolean indexInitialized = new AtomicBoolean(false);
    private final Object indexLifecycleMonitor = new Object();
    private final Set<String> indexedDocumentIds = new LinkedHashSet<>();

    @Autowired
    public WorkflowKnowledgeVectorStoreService(@Qualifier("workflowKnowledgeVectorStore") VectorStore vectorStore,
                                               List<WorkflowContextStrategy> workflowStrategies,
                                               IncidentResolutionRuleRepository ruleRepository,
                                               BpmnKnowledgeExtractor bpmnKnowledgeExtractor,
                                               @Value("${app.ai.retrieval.enabled:true}") boolean retrievalEnabled,
                                               @Value("${app.ai.retrieval.top-k:4}") int topK,
                                               @Value("${app.ai.retrieval.store-path:data/ai/workflow-knowledge-vector-store.json}") String storePath) {
        this.vectorStore = vectorStore;
        this.workflowStrategies = workflowStrategies == null ? List.of() : List.copyOf(workflowStrategies);
        this.ruleRepository = ruleRepository;
        this.bpmnKnowledgeExtractor = bpmnKnowledgeExtractor;
        this.retrievalEnabled = retrievalEnabled;
        this.topK = Math.max(topK, 1);
        this.storePath = storePath == null ? "" : storePath;
    }

    public String fetchRelevantContext(String userPrompt, Optional<WorkflowContextStrategy> strategyOpt) {
        if (!retrievalEnabled || strategyOpt.isEmpty()) {
            return "No retrieved workflow knowledge was added.";
        }

        WorkflowContextStrategy strategy = strategyOpt.get();
        if (!indexInitialized.get()) {
            return buildFallbackContext(strategy);
        }

        String vectorContext = fetchVectorContext(userPrompt, strategy);
        if (StringUtils.hasText(vectorContext) && !"No retrieved workflow knowledge was added.".equals(vectorContext)) {
            return vectorContext;
        }
        return buildFallbackContext(strategy);
    }

    public void initializeIndexOnStartup() {
        if (!retrievalEnabled) {
            log.info("[WORKFLOW KNOWLEDGE INDEX] Retrieval disabled; skipping startup initialization.");
            return;
        }

        refreshIndex();
        log.info("[WORKFLOW KNOWLEDGE INDEX] Startup initialization completed with {} document(s).", indexedDocumentIds.size());
    }

    public void refreshIndex() {
        if (!retrievalEnabled) {
            return;
        }

        synchronized (indexLifecycleMonitor) {
            List<Document> documents = buildKnowledgeDocuments();
            replaceIndexedCorpus(documents);
            persistIndexIfSupported();
            indexInitialized.set(true);
        }
    }

    private List<Document> buildKnowledgeDocuments() {
        Map<String, Document> documentsById = new LinkedHashMap<>();
        Map<String, BpmnProcessKnowledge> bpmnProcesses = bpmnKnowledgeExtractor.extractProcesses();
        for (WorkflowContextStrategy strategy : workflowStrategies) {
            documentsById.put("strategy-context-" + strategy.getProcessDefinitionId(), Document.builder()
                    .id("strategy-context-" + strategy.getProcessDefinitionId())
                    .text("Workflow process definition: " + strategy.getProcessDefinitionId()
                            + "\n" + strategy.generateBpmnContextInstructions()
                            + "\n" + strategy.generateBusinessIdentifierInstructions()
                            + "\n" + strategy.generateReportStructuringInstructions())
                    .metadata(Map.of(
                            "workflowProcessDefinitionId", strategy.getProcessDefinitionId(),
                            "knowledgeType", "workflow-context"
                    ))
                    .build());
        }

        for (IncidentResolutionRuleEntity entity : ruleRepository.findAllByOrderByWorkflowProcessDefinitionIdAscPriorityAscIdAsc()) {
            documentsById.put("rule-" + entity.getId(), Document.builder()
                    .id("rule-" + entity.getId())
                    .text(buildRuleKnowledgeText(entity))
                    .metadata(Map.of(
                            "workflowProcessDefinitionId", entity.getWorkflowProcessDefinitionId(),
                            "knowledgeType", "incident-rule",
                            "ruleId", entity.getId()
                    ))
                    .build());
        }

        for (WorkflowContextStrategy strategy : workflowStrategies) {
            for (Document bpmnDocument : buildBpmnDocuments(strategy, bpmnProcesses)) {
                documentsById.put(bpmnDocument.getId(), bpmnDocument);
            }
        }

        return List.copyOf(documentsById.values());
    }

    private List<Document> buildBpmnDocuments(WorkflowContextStrategy strategy, Map<String, BpmnProcessKnowledge> bpmnProcesses) {
        List<Document> documents = new ArrayList<>();
        if (bpmnProcesses.isEmpty()) {
            return documents;
        }

        String workflowScopeId = strategy.getProcessDefinitionId();
        Set<String> reachableProcessIds = bpmnKnowledgeExtractor.resolveReachableProcessIds(workflowScopeId, bpmnProcesses);
        for (String processId : reachableProcessIds) {
            BpmnProcessKnowledge process = bpmnProcesses.get(processId);
            if (process == null) {
                continue;
            }
            documents.add(buildBpmnSummaryDocument(workflowScopeId, process));
            if (!process.callActivities().isEmpty()) {
                documents.add(buildBpmnCallActivityDocument(workflowScopeId, process));
            }
            if (!process.serviceTasks().isEmpty()) {
                documents.add(buildBpmnServiceTaskDocument(workflowScopeId, process));
            }
            if (!process.gateways().isEmpty()) {
                documents.add(buildBpmnGatewayDocument(workflowScopeId, process));
            }
            if (!process.boundaryEvents().isEmpty()) {
                documents.add(buildBpmnBoundaryEventDocument(workflowScopeId, process));
            }
        }
        return documents;
    }

    private String fetchVectorContext(String userPrompt, WorkflowContextStrategy strategy) {
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(userPrompt)
                .topK(topK)
                .build());
        if (documents == null || documents.isEmpty()) {
            return "No retrieved workflow knowledge was added.";
        }

        String workflowId = strategy.getProcessDefinitionId();
        return documents.stream()
                .filter(document -> workflowId.equals(String.valueOf(document.getMetadata().get("workflowProcessDefinitionId"))))
                .map(Document::getText)
                .filter(StringUtils::hasText)
                .distinct()
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("No retrieved workflow knowledge was added.");
    }

    private Document buildBpmnSummaryDocument(String workflowScopeId, BpmnProcessKnowledge process) {
        return Document.builder()
                .id("bpmn-summary-" + workflowScopeId + "-" + process.processId())
                .text("""
                        BPMN process summary
                        Workflow scope: %s
                        BPMN process definition: %s
                        BPMN process name: %s
                        Source file: %s
                        Service task count: %d
                        Call activity count: %d
                        Gateway count: %d
                        Boundary event count: %d
                        """.formatted(
                        workflowScopeId,
                        defaultText(process.processId()),
                        defaultText(process.processName()),
                        defaultText(process.sourceFile()),
                        process.serviceTasks().size(),
                        process.callActivities().size(),
                        process.gateways().size(),
                        process.boundaryEvents().size()
                ).trim())
                .metadata(buildBpmnMetadata(workflowScopeId, process, "process-summary"))
                .build();
    }

    private Document buildBpmnCallActivityDocument(String workflowScopeId, BpmnProcessKnowledge process) {
        String callActivityLines = process.callActivities().stream()
                .map(callActivity -> "- Call activity %s / %s -> called process %s".formatted(
                        defaultText(callActivity.elementId()),
                        defaultText(callActivity.elementName()),
                        defaultText(callActivity.calledProcessId())
                ))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- No call activities.");
        return Document.builder()
                .id("bpmn-call-activities-" + workflowScopeId + "-" + process.processId())
                .text("""
                        BPMN call activity map
                        Workflow scope: %s
                        BPMN process definition: %s
                        BPMN process name: %s
                        Source file: %s
                        %s
                        """.formatted(
                        workflowScopeId,
                        defaultText(process.processId()),
                        defaultText(process.processName()),
                        defaultText(process.sourceFile()),
                        callActivityLines
                ).trim())
                .metadata(buildBpmnMetadata(workflowScopeId, process, "call-activity"))
                .build();
    }

    private Document buildBpmnServiceTaskDocument(String workflowScopeId, BpmnProcessKnowledge process) {
        String serviceTaskLines = process.serviceTasks().stream()
                .map(task -> "- Service task %s / %s / worker type %s".formatted(
                        defaultText(task.elementId()),
                        defaultText(task.elementName()),
                        defaultText(task.taskType())
                ))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- No service tasks.");
        return Document.builder()
                .id("bpmn-service-tasks-" + workflowScopeId + "-" + process.processId())
                .text("""
                        BPMN service task map
                        Workflow scope: %s
                        BPMN process definition: %s
                        BPMN process name: %s
                        Source file: %s
                        %s
                        """.formatted(
                        workflowScopeId,
                        defaultText(process.processId()),
                        defaultText(process.processName()),
                        defaultText(process.sourceFile()),
                        serviceTaskLines
                ).trim())
                .metadata(buildBpmnMetadata(workflowScopeId, process, "service-task"))
                .build();
    }

    private Document buildBpmnGatewayDocument(String workflowScopeId, BpmnProcessKnowledge process) {
        String gatewayLines = process.gateways().stream()
                .map(gateway -> "- Gateway %s / %s / type %s".formatted(
                        defaultText(gateway.elementId()),
                        defaultText(gateway.elementName()),
                        defaultText(gateway.elementType())
                ))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- No gateways.");
        return Document.builder()
                .id("bpmn-gateways-" + workflowScopeId + "-" + process.processId())
                .text("""
                        BPMN routing control points
                        Workflow scope: %s
                        BPMN process definition: %s
                        BPMN process name: %s
                        Source file: %s
                        %s
                        """.formatted(
                        workflowScopeId,
                        defaultText(process.processId()),
                        defaultText(process.processName()),
                        defaultText(process.sourceFile()),
                        gatewayLines
                ).trim())
                .metadata(buildBpmnMetadata(workflowScopeId, process, "gateway"))
                .build();
    }

    private Document buildBpmnBoundaryEventDocument(String workflowScopeId, BpmnProcessKnowledge process) {
        String boundaryEventLines = process.boundaryEvents().stream()
                .map(boundaryEvent -> "- Boundary event %s / %s / attached to %s / definition %s".formatted(
                        defaultText(boundaryEvent.elementId()),
                        defaultText(boundaryEvent.elementName()),
                        defaultText(boundaryEvent.attachedToRef()),
                        defaultText(boundaryEvent.definitionType())
                ))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- No boundary events.");
        return Document.builder()
                .id("bpmn-boundary-events-" + workflowScopeId + "-" + process.processId())
                .text("""
                        BPMN boundary and escalation points
                        Workflow scope: %s
                        BPMN process definition: %s
                        BPMN process name: %s
                        Source file: %s
                        %s
                        """.formatted(
                        workflowScopeId,
                        defaultText(process.processId()),
                        defaultText(process.processName()),
                        defaultText(process.sourceFile()),
                        boundaryEventLines
                ).trim())
                .metadata(buildBpmnMetadata(workflowScopeId, process, "boundary-event"))
                .build();
    }

    private Map<String, Object> buildBpmnMetadata(String workflowScopeId, BpmnProcessKnowledge process, String elementType) {
        return Map.of(
                "workflowProcessDefinitionId", workflowScopeId,
                "knowledgeType", "bpmn",
                "sourceFile", defaultText(process.sourceFile()),
                "bpmnProcessDefinitionId", defaultText(process.processId()),
                "elementType", elementType
        );
    }

    private void replaceIndexedCorpus(List<Document> documents) {
        if (!indexedDocumentIds.isEmpty()) {
            vectorStore.delete(List.copyOf(indexedDocumentIds));
            indexedDocumentIds.clear();
        }

        if (documents.isEmpty()) {
            return;
        }

        vectorStore.add(documents);
        documents.stream()
                .map(Document::getId)
                .filter(StringUtils::hasText)
                .forEach(indexedDocumentIds::add);
    }

    private void persistIndexIfSupported() {
        if (!(vectorStore instanceof SimpleVectorStore simpleVectorStore) || !StringUtils.hasText(storePath)) {
            return;
        }

        File storeFile = new File(storePath);
        File parent = storeFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            log.warn("[WORKFLOW KNOWLEDGE INDEX] Failed to create parent directory for {}", storePath);
            return;
        }

        simpleVectorStore.save(storeFile);
    }

    private String buildFallbackContext(WorkflowContextStrategy strategy) {
        List<String> sections = new ArrayList<>();
        sections.add("Workflow strategy: " + strategy.getProcessDefinitionId());
        sections.add(strategy.generateBpmnContextInstructions());
        sections.add(strategy.generateBusinessIdentifierInstructions());
        sections.add(strategy.generateReportStructuringInstructions());
        return sections.stream()
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("No retrieved workflow knowledge was added.");
    }

    private String buildRuleKnowledgeText(IncidentResolutionRuleEntity entity) {
        return """
                Workflow process definition: %s
                Rule instruction: %s
                Error types: %s
                HTTP status codes: %s
                Message contains: %s
                Resolution mode: %s
                Reason: %s
                Guidance: %s
                """.formatted(
                entity.getWorkflowProcessDefinitionId(),
                defaultText(entity.getInstruction()),
                defaultText(entity.getErrorTypes()),
                defaultText(entity.getHttpStatusCodes()),
                defaultText(entity.getMessageContains()),
                defaultText(entity.getResolutionMode()),
                defaultText(entity.getReason()),
                defaultText(entity.getUserFacingGuidance())
        ).trim();
    }

    private String defaultText(String value) {
        return StringUtils.hasText(value) ? value : "none";
    }
}
