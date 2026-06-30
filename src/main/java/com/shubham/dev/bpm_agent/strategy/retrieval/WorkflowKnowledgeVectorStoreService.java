package com.shubham.dev.bpm_agent.strategy.retrieval;

import com.shubham.dev.bpm_agent.strategy.WorkflowContextStrategy;
import com.shubham.dev.bpm_agent.strategy.persistence.IncidentResolutionRuleEntity;
import com.shubham.dev.bpm_agent.strategy.persistence.IncidentResolutionRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class WorkflowKnowledgeVectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowKnowledgeVectorStoreService.class);

    private final VectorStore vectorStore;
    private final List<WorkflowContextStrategy> workflowStrategies;
    private final IncidentResolutionRuleRepository ruleRepository;
    private final boolean retrievalEnabled;
    private final int topK;
    private final File storeFile;

    private final Set<String> indexedDocumentIds = new LinkedHashSet<>();
    private volatile boolean initialized;

    public WorkflowKnowledgeVectorStoreService(@Qualifier("workflowKnowledgeVectorStore") VectorStore vectorStore,
                                               List<WorkflowContextStrategy> workflowStrategies,
                                               IncidentResolutionRuleRepository ruleRepository,
                                               @Value("${app.ai.retrieval.enabled:true}") boolean retrievalEnabled,
                                               @Value("${app.ai.retrieval.top-k:4}") int topK,
                                               @Value("${app.ai.retrieval.store-path:data/ai/workflow-knowledge-vector-store.json}") String storePath) {
        this.vectorStore = vectorStore;
        this.workflowStrategies = workflowStrategies;
        this.ruleRepository = ruleRepository;
        this.retrievalEnabled = retrievalEnabled;
        this.topK = topK;
        this.storeFile = new File(storePath);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        refreshIndex();
    }

    public synchronized void refreshIndex() {
        if (!retrievalEnabled) {
            initialized = true;
            return;
        }

        try {
            List<Document> documents = buildKnowledgeDocuments();
            if (!indexedDocumentIds.isEmpty()) {
                vectorStore.delete(new ArrayList<>(indexedDocumentIds));
                indexedDocumentIds.clear();
            }

            if (!documents.isEmpty()) {
                vectorStore.add(documents);
                documents.stream()
                        .map(Document::getId)
                        .filter(StringUtils::hasText)
                        .forEach(indexedDocumentIds::add);
            }

            persistStore();
            initialized = true;
            log.info("[WORKFLOW KNOWLEDGE INDEX] Indexed {} workflow knowledge documents.", documents.size());
        } catch (Exception ex) {
            log.warn("[WORKFLOW KNOWLEDGE INDEX] Failed to refresh vector knowledge index: {}", ex.getMessage());
        }
    }

    public String fetchRelevantContext(String userPrompt, Optional<WorkflowContextStrategy> strategyOpt) {
        if (!retrievalEnabled || !StringUtils.hasText(userPrompt)) {
            return "";
        }
        ensureInitialized();

        try {
            List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(userPrompt)
                    .topK(Math.max(1, topK))
                    .similarityThresholdAll()
                    .build());

            List<Document> filteredResults = results.stream()
                    .filter(document -> matchesStrategy(document, strategyOpt))
                    .limit(Math.max(1, topK))
                    .toList();

            if (filteredResults.isEmpty()) {
                return "";
            }

            StringBuilder context = new StringBuilder();
            context.append("RETRIEVED WORKFLOW KNOWLEDGE:\n");
            int index = 1;
            for (Document document : filteredResults) {
                context.append(index++)
                        .append(". [")
                        .append(document.getMetadata().getOrDefault("knowledgeType", "knowledge"))
                        .append(" | ")
                        .append(document.getMetadata().getOrDefault("workflowProcessDefinitionId", "shared"))
                        .append("] ")
                        .append(document.getText())
                        .append('\n');
            }
            return context.toString().trim();
        } catch (Exception ex) {
            log.warn("[WORKFLOW KNOWLEDGE INDEX] Similarity search failed: {}", ex.getMessage());
            return "";
        }
    }

    private void ensureInitialized() {
        if (!initialized) {
            refreshIndex();
        }
    }

    private boolean matchesStrategy(Document document, Optional<WorkflowContextStrategy> strategyOpt) {
        if (strategyOpt.isEmpty()) {
            return true;
        }

        Object workflowId = document.getMetadata().get("workflowProcessDefinitionId");
        return workflowId == null
                || strategyOpt.get().getProcessDefinitionId().equals(String.valueOf(workflowId));
    }

    private List<Document> buildKnowledgeDocuments() {
        List<Document> documents = new ArrayList<>();
        for (WorkflowContextStrategy strategy : workflowStrategies) {
            documents.add(Document.builder()
                    .id("strategy-context-" + strategy.getProcessDefinitionId())
                    .text(buildStrategyContext(strategy))
                    .metadata(Map.of(
                            "workflowProcessDefinitionId", strategy.getProcessDefinitionId(),
                            "knowledgeType", "workflow-context"
                    ))
                    .build());

            documents.add(Document.builder()
                    .id("strategy-report-" + strategy.getProcessDefinitionId())
                    .text(buildStrategyReportGuide(strategy))
                    .metadata(Map.of(
                            "workflowProcessDefinitionId", strategy.getProcessDefinitionId(),
                            "knowledgeType", "report-guidance"
                    ))
                    .build());
        }

        for (IncidentResolutionRuleEntity entity : ruleRepository.findAllByOrderByWorkflowProcessDefinitionIdAscPriorityAscIdAsc()) {
            documents.add(Document.builder()
                    .id("incident-rule-" + entity.getId())
                    .text(buildRuleKnowledge(entity))
                    .metadata(Map.of(
                            "workflowProcessDefinitionId", entity.getWorkflowProcessDefinitionId(),
                            "knowledgeType", "incident-rule",
                            "enabled", entity.isEnabled()
                    ))
                    .build());
        }

        return documents;
    }

    private String buildStrategyContext(WorkflowContextStrategy strategy) {
        return """
                Workflow process definition: %s
                Primary business identifier: %s
                Business identifier normalization:
                %s

                BPMN operating context:
                %s
                """.formatted(
                strategy.getProcessDefinitionId(),
                strategy.primaryBusinessIdentifierVariable(),
                blankToFallback(strategy.generateBusinessIdentifierInstructions(), "No extra business identifier rules were provided."),
                strategy.generateBpmnContextInstructions()
        ).trim();
    }

    private String buildStrategyReportGuide(WorkflowContextStrategy strategy) {
        return """
                Workflow process definition: %s
                Report structuring guidance:
                %s
                """.formatted(
                strategy.getProcessDefinitionId(),
                strategy.generateReportStructuringInstructions()
        ).trim();
    }

    private String buildRuleKnowledge(IncidentResolutionRuleEntity entity) {
        return """
                Workflow process definition: %s
                Rule priority: %s
                Rule enabled: %s
                Resolution mode: %s
                Instruction: %s
                Error types: %s
                HTTP status codes: %s
                Message contains: %s
                Reason: %s
                User guidance: %s
                """.formatted(
                entity.getWorkflowProcessDefinitionId(),
                entity.getPriority(),
                entity.isEnabled(),
                entity.getResolutionMode(),
                entity.getInstruction(),
                blankToFallback(entity.getErrorTypes(), "none"),
                blankToFallback(entity.getHttpStatusCodes(), "none"),
                blankToFallback(entity.getMessageContains(), "none"),
                entity.getReason(),
                blankToFallback(entity.getUserFacingGuidance(), "none")
        ).trim();
    }

    private String blankToFallback(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private void persistStore() {
        if (vectorStore instanceof SimpleVectorStore simpleVectorStore) {
            File parent = storeFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            simpleVectorStore.save(storeFile);
        }
    }
}
