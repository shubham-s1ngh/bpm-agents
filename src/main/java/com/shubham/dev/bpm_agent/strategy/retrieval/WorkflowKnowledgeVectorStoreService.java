package com.shubham.dev.bpm_agent.strategy.retrieval;

import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionRule;
import com.shubham.dev.bpm_agent.strategy.WorkflowContextStrategy;
import com.shubham.dev.bpm_agent.strategy.persistence.IncidentResolutionRuleCatalogService;
import com.shubham.dev.bpm_agent.strategy.persistence.IncidentResolutionRuleEntity;
import com.shubham.dev.bpm_agent.strategy.persistence.IncidentResolutionRuleRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class WorkflowKnowledgeVectorStoreService {

    private final IncidentResolutionRuleCatalogService ruleCatalogService;
    private final VectorStore vectorStore;
    private final List<WorkflowContextStrategy> workflowStrategies;
    private final IncidentResolutionRuleRepository ruleRepository;
    private final boolean retrievalEnabled;
    private final int topK;
    private final String storePath;

    @Autowired
    public WorkflowKnowledgeVectorStoreService(IncidentResolutionRuleCatalogService ruleCatalogService,
                                               @Value("${app.ai.retrieval.enabled:true}") boolean retrievalEnabled) {
        this.ruleCatalogService = ruleCatalogService;
        this.vectorStore = null;
        this.workflowStrategies = List.of();
        this.ruleRepository = null;
        this.retrievalEnabled = retrievalEnabled;
        this.topK = 4;
        this.storePath = "";
    }

    public WorkflowKnowledgeVectorStoreService(VectorStore vectorStore,
                                               List<WorkflowContextStrategy> workflowStrategies,
                                               IncidentResolutionRuleRepository ruleRepository,
                                               boolean retrievalEnabled,
                                               int topK,
                                               String storePath) {
        this.ruleCatalogService = null;
        this.vectorStore = vectorStore;
        this.workflowStrategies = workflowStrategies == null ? List.of() : List.copyOf(workflowStrategies);
        this.ruleRepository = ruleRepository;
        this.retrievalEnabled = retrievalEnabled;
        this.topK = topK;
        this.storePath = storePath == null ? "" : storePath;
    }

    public String fetchRelevantContext(String userPrompt, Optional<WorkflowContextStrategy> strategyOpt) {
        if (!retrievalEnabled || strategyOpt.isEmpty()) {
            return "No retrieved workflow knowledge was added.";
        }

        WorkflowContextStrategy strategy = strategyOpt.get();
        if (vectorStore != null) {
            return fetchVectorContext(userPrompt, strategy);
        }

        List<String> sections = new ArrayList<>();
        sections.add("Workflow strategy: " + strategy.getProcessDefinitionId());
        sections.add(strategy.generateBpmnContextInstructions());

        List<IncidentResolutionRule> rules = ruleCatalogService == null
                ? List.of()
                : ruleCatalogService.findRulesForWorkflows(Set.of(strategy.getProcessDefinitionId()));
        if (!rules.isEmpty()) {
            StringBuilder persistedRules = new StringBuilder("Consultant-managed incident rules:\n");
            for (IncidentResolutionRule rule : rules) {
                persistedRules.append("- ").append(rule.mode()).append(": ").append(rule.reason()).append('\n');
            }
            sections.add(persistedRules.toString().trim());
        }

        String bpmnSummary = loadBpmnSummaries(userPrompt, strategy.getProcessDefinitionId());
        if (StringUtils.hasText(bpmnSummary)) {
            sections.add(bpmnSummary);
        }

        return String.join("\n\n", sections);
    }

    public void refreshIndex() {
        if (!retrievalEnabled || vectorStore == null) {
            return;
        }

        List<Document> documents = new ArrayList<>();
        for (WorkflowContextStrategy strategy : workflowStrategies) {
            documents.add(Document.builder()
                    .id("strategy-context-" + strategy.getProcessDefinitionId())
                    .text("Workflow process definition: " + strategy.getProcessDefinitionId()
                            + "\n" + strategy.generateBpmnContextInstructions()
                            + "\n" + strategy.generateBusinessIdentifierInstructions()
                            + "\n" + strategy.generateReportStructuringInstructions())
                    .metadata(Map.of(
                            "workflowProcessDefinitionId", strategy.getProcessDefinitionId(),
                            "knowledgeType", "workflow-context",
                            "storePath", storePath
                    ))
                    .build());
        }

        if (ruleRepository != null) {
            for (IncidentResolutionRuleEntity entity : ruleRepository.findAllByOrderByWorkflowProcessDefinitionIdAscPriorityAscIdAsc()) {
                documents.add(Document.builder()
                        .id("rule-" + entity.getId())
                        .text(entity.getInstruction() + "\n" + entity.getReason())
                        .metadata(Map.of(
                                "workflowProcessDefinitionId", entity.getWorkflowProcessDefinitionId(),
                                "knowledgeType", "incident-rule",
                                "storePath", storePath
                        ))
                        .build());
            }
        }

        if (!documents.isEmpty()) {
            vectorStore.add(documents);
        }
    }

    private String loadBpmnSummaries(String userPrompt, String processDefinitionId) {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:bpmns/*.bpmn");
            StringBuilder summary = new StringBuilder("BPMN artifacts:\n");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (!StringUtils.hasText(filename)) {
                    continue;
                }
                if (!filename.toLowerCase().contains(processDefinitionId.toLowerCase().replace("id", "")) && !userPrompt.toLowerCase().contains("bpmn")) {
                    continue;
                }
                summary.append("- ").append(filename).append('\n');
                String content = resource.getContentAsString(StandardCharsets.UTF_8);
                summary.append("  Preview: ").append(firstMeaningfulLine(content)).append('\n');
            }
            return summary.length() == "BPMN artifacts:\n".length() ? "" : summary.toString().trim();
        } catch (IOException ignored) {
            return "";
        }
    }

    private String firstMeaningfulLine(String content) {
        return content.lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse("No BPMN preview available.");
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
}
