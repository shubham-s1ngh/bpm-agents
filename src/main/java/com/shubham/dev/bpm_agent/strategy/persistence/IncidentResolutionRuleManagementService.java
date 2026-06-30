package com.shubham.dev.bpm_agent.strategy.persistence;

import com.shubham.dev.bpm_agent.chat.model.incident.IncidentResolutionMode;
import com.shubham.dev.bpm_agent.strategy.WorkflowStrategyRegistry;
import com.shubham.dev.bpm_agent.strategy.admin.IncidentResolutionRuleAdminMetadata;
import com.shubham.dev.bpm_agent.strategy.admin.IncidentResolutionRuleAdminRecord;
import com.shubham.dev.bpm_agent.strategy.admin.IncidentResolutionRuleUpsertRequest;
import com.shubham.dev.bpm_agent.strategy.retrieval.WorkflowKnowledgeVectorStoreService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class IncidentResolutionRuleManagementService {

    private final IncidentResolutionRuleRepository repository;
    private final WorkflowStrategyRegistry workflowStrategyRegistry;
    private final WorkflowKnowledgeVectorStoreService workflowKnowledgeVectorStoreService;

    public IncidentResolutionRuleManagementService(IncidentResolutionRuleRepository repository,
                                                   WorkflowStrategyRegistry workflowStrategyRegistry,
                                                   WorkflowKnowledgeVectorStoreService workflowKnowledgeVectorStoreService) {
        this.repository = repository;
        this.workflowStrategyRegistry = workflowStrategyRegistry;
        this.workflowKnowledgeVectorStoreService = workflowKnowledgeVectorStoreService;
    }

    @Transactional(readOnly = true)
    public List<IncidentResolutionRuleAdminRecord> listRules(Optional<String> workflowProcessDefinitionId) {
        List<IncidentResolutionRuleEntity> entities = workflowProcessDefinitionId
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(repository::findByWorkflowProcessDefinitionIdOrderByPriorityAscIdAsc)
                .orElseGet(repository::findAllByOrderByWorkflowProcessDefinitionIdAscPriorityAscIdAsc);

        return entities.stream()
                .map(this::toAdminRecord)
                .toList();
    }

    @Transactional(readOnly = true)
    public IncidentResolutionRuleAdminMetadata fetchMetadata() {
        Set<String> workflowIds = new LinkedHashSet<>(workflowStrategyRegistry.getRegisteredProcessDefinitionIds());
        workflowIds.addAll(repository.findDistinctWorkflowProcessDefinitionIds());
        List<String> sortedWorkflowIds = workflowIds.stream().sorted().toList();
        return new IncidentResolutionRuleAdminMetadata(
                sortedWorkflowIds,
                Arrays.stream(IncidentResolutionMode.values()).map(Enum::name).toList()
        );
    }

    @Transactional
    public IncidentResolutionRuleAdminRecord createRule(IncidentResolutionRuleUpsertRequest request) {
        IncidentResolutionRuleEntity entity = new IncidentResolutionRuleEntity();
        applyRequest(entity, request);
        IncidentResolutionRuleAdminRecord saved = toAdminRecord(repository.save(entity));
        workflowKnowledgeVectorStoreService.refreshIndex();
        return saved;
    }

    @Transactional
    public IncidentResolutionRuleAdminRecord updateRule(Long id, IncidentResolutionRuleUpsertRequest request) {
        IncidentResolutionRuleEntity entity = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Incident resolution rule " + id + " was not found."));
        applyRequest(entity, request);
        IncidentResolutionRuleAdminRecord saved = toAdminRecord(repository.save(entity));
        workflowKnowledgeVectorStoreService.refreshIndex();
        return saved;
    }

    @Transactional
    public IncidentResolutionRuleAdminRecord setEnabled(Long id, boolean enabled) {
        IncidentResolutionRuleEntity entity = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Incident resolution rule " + id + " was not found."));
        entity.setEnabled(enabled);
        IncidentResolutionRuleAdminRecord saved = toAdminRecord(repository.save(entity));
        workflowKnowledgeVectorStoreService.refreshIndex();
        return saved;
    }

    @Transactional
    public void deleteRule(Long id) {
        if (!repository.existsById(id)) {
            throw new EntityNotFoundException("Incident resolution rule " + id + " was not found.");
        }
        repository.deleteById(id);
        workflowKnowledgeVectorStoreService.refreshIndex();
    }

    private IncidentResolutionRuleAdminRecord toAdminRecord(IncidentResolutionRuleEntity entity) {
        return new IncidentResolutionRuleAdminRecord(
                entity.getId(),
                entity.getWorkflowProcessDefinitionId(),
                entity.getPriority(),
                entity.isEnabled(),
                entity.getInstruction(),
                parseCsvStrings(entity.getErrorTypes()),
                parseCsvIntegers(entity.getHttpStatusCodes()),
                parseCsvStrings(entity.getMessageContains()),
                entity.getResolutionMode(),
                entity.getReason(),
                entity.getUserFacingGuidance() == null ? "" : entity.getUserFacingGuidance()
        );
    }

    private void applyRequest(IncidentResolutionRuleEntity entity, IncidentResolutionRuleUpsertRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body must not be empty.");
        }

        String workflowId = requiredText(request.workflowProcessDefinitionId(), "workflowProcessDefinitionId");
        String instruction = requiredText(request.instruction(), "instruction");
        String reason = requiredText(request.reason(), "reason");
        IncidentResolutionMode mode = parseMode(request.resolutionMode());

        if (request.priority() == null) {
            throw new IllegalArgumentException("priority must not be empty.");
        }

        entity.setWorkflowProcessDefinitionId(workflowId);
        entity.setPriority(request.priority());
        entity.setEnabled(request.enabled());
        entity.setInstruction(instruction);
        entity.setErrorTypes(joinCsv(normalizeUppercaseTokens(request.errorTypes())));
        entity.setHttpStatusCodes(joinCsv(normalizeHttpStatusCodes(request.httpStatusCodes())));
        entity.setMessageContains(joinCsv(normalizeMessageContains(request.messageContains())));
        entity.setResolutionMode(mode.name());
        entity.setReason(reason);
        entity.setUserFacingGuidance(trimToEmpty(request.userFacingGuidance()));
    }

    private IncidentResolutionMode parseMode(String value) {
        String normalized = requiredText(value, "resolutionMode").toUpperCase(Locale.ROOT);
        try {
            return IncidentResolutionMode.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("resolutionMode must be one of " + List.of(IncidentResolutionMode.values()) + ".");
        }
    }

    private List<String> normalizeUppercaseTokens(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values == null) {
            return List.of();
        }
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            normalized.add(value.trim().toUpperCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    private List<Integer> normalizeHttpStatusCodes(List<Integer> values) {
        LinkedHashSet<Integer> normalized = new LinkedHashSet<>();
        if (values == null) {
            return List.of();
        }
        for (Integer value : values) {
            if (value == null) {
                continue;
            }
            if (value < 100 || value > 599) {
                throw new IllegalArgumentException("httpStatusCodes must contain valid HTTP status codes between 100 and 599.");
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    private List<String> normalizeMessageContains(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values == null) {
            return List.of();
        }
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            normalized.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    private List<String> parseCsvStrings(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String token : value.split(",")) {
            if (StringUtils.hasText(token)) {
                values.add(token.trim());
            }
        }
        return List.copyOf(values);
    }

    private List<Integer> parseCsvIntegers(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        List<Integer> values = new ArrayList<>();
        for (String token : value.split(",")) {
            if (StringUtils.hasText(token)) {
                values.add(Integer.parseInt(token.trim()));
            }
        }
        return List.copyOf(values);
    }

    private String joinCsv(List<?> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream()
                .map(String::valueOf)
                .reduce((left, right) -> left + "," + right)
                .orElse(null);
    }

    private String requiredText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " must not be empty.");
        }
        return value.trim();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
