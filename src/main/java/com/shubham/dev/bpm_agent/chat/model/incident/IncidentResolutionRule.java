package com.shubham.dev.bpm_agent.chat.model.incident;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record IncidentResolutionRule(
        String instruction,
        Set<String> processDefinitionIds,
        Set<String> errorTypes,
        Set<Integer> httpStatusCodes,
        List<String> messageContains,
        IncidentResolutionMode mode,
        String reason,
        String userFacingGuidance
) {
    private static final Pattern HTTP_STATUS_PATTERN = Pattern.compile("\\bHTTP\\s+(\\d{3})\\b", Pattern.CASE_INSENSITIVE);

    public IncidentResolutionRule {
        processDefinitionIds = normalizeSet(processDefinitionIds);
        errorTypes = normalizeSet(errorTypes);
        messageContains = normalizeList(messageContains);
        Objects.requireNonNull(mode, "mode must not be null");
    }

    public boolean matches(IncidentResolutionContext context, Map<String, Object> incident) {
        if (!processDefinitionIds.isEmpty() && !matchesProcessDefinitionIds(context)) {
            return false;
        }

        String errorType = normalize(incident.get("errorType"));
        if (!errorTypes.isEmpty() && !errorTypes.contains(errorType)) {
            return false;
        }

        String incidentText = buildSearchableIncidentText(incident);
        if (!messageContains.isEmpty() && messageContains.stream().noneMatch(incidentText::contains)) {
            return false;
        }

        Integer httpStatus = extractHttpStatus(incidentText);
        if (!httpStatusCodes.isEmpty() && !httpStatusCodes.contains(httpStatus)) {
            return false;
        }

        return true;
    }

    private boolean matchesProcessDefinitionIds(IncidentResolutionContext context) {
        Set<String> involvedProcessDefinitionIds = context.involvedProcessDefinitionIds();
        if (involvedProcessDefinitionIds != null && !involvedProcessDefinitionIds.isEmpty()) {
            return involvedProcessDefinitionIds.stream()
                    .map(IncidentResolutionRule::normalize)
                    .anyMatch(processDefinitionIds::contains);
        }
        return processDefinitionIds.contains(normalize(context.processDefinitionId()));
    }

    public IncidentResolutionDecision toDecision() {
        if (mode == IncidentResolutionMode.BLOCKED) {
            return IncidentResolutionDecision.blocked(reason, userFacingGuidance);
        }
        if (mode == IncidentResolutionMode.NO_ACTION) {
            return IncidentResolutionDecision.noAction(reason, userFacingGuidance);
        }
        return IncidentResolutionDecision.allowed(mode, reason);
    }

    private static String buildSearchableIncidentText(Map<String, Object> incident) {
        return incident.values().stream()
                .filter(Objects::nonNull)
                .map(IncidentResolutionRule::normalize)
                .reduce("", (left, right) -> left + " " + right)
                .trim();
    }

    private static Integer extractHttpStatus(String incidentText) {
        Matcher matcher = HTTP_STATUS_PATTERN.matcher(incidentText);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private static Set<String> normalizeSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return values.stream().filter(Objects::nonNull).map(IncidentResolutionRule::normalize).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).map(IncidentResolutionRule::normalize).toList();
    }

    private static String normalize(Object value) {
        return Objects.toString(value, "").toLowerCase(Locale.ROOT);
    }
}
