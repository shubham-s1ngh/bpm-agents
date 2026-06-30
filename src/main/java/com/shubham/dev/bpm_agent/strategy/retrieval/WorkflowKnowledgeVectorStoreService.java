package com.shubham.dev.bpm_agent.strategy.retrieval;

import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionRule;
import com.shubham.dev.bpm_agent.strategy.WorkflowContextStrategy;
import com.shubham.dev.bpm_agent.strategy.persistence.IncidentResolutionRuleCatalogService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class WorkflowKnowledgeVectorStoreService {

    private final IncidentResolutionRuleCatalogService ruleCatalogService;
    private final boolean retrievalEnabled;

    public WorkflowKnowledgeVectorStoreService(IncidentResolutionRuleCatalogService ruleCatalogService,
                                               @Value("${app.ai.retrieval.enabled:true}") boolean retrievalEnabled) {
        this.ruleCatalogService = ruleCatalogService;
        this.retrievalEnabled = retrievalEnabled;
    }

    public String fetchRelevantContext(String userPrompt, Optional<WorkflowContextStrategy> strategyOpt) {
        if (!retrievalEnabled || strategyOpt.isEmpty()) {
            return "No retrieved workflow knowledge was added.";
        }

        WorkflowContextStrategy strategy = strategyOpt.get();
        List<String> sections = new ArrayList<>();
        sections.add("Workflow strategy: " + strategy.getProcessDefinitionId());
        sections.add(strategy.generateBpmnContextInstructions());

        List<IncidentResolutionRule> rules = ruleCatalogService.findRulesForWorkflows(Set.of(strategy.getProcessDefinitionId()));
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
}
