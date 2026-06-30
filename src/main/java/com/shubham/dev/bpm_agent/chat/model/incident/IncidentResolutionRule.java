package com.shubham.dev.bpm_agent.chat.model.incident;

import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record IncidentResolutionRule(String instruction,
                                     Set<String> processDefinitionIds,
                                     Set<String> errorTypes,
                                     Set<Integer> httpStatusCodes,
                                     List<String> messageContains,
                                     IncidentResolutionMode mode,
                                     String reason,
                                     String userFacingGuidance) {

    private static final Pattern HTTP_STATUS_PATTERN = Pattern.compile("\\bHTTP\\s+(\\d{3})\\b", Pattern.CASE_INSENSITIVE);

    public IncidentResolutionRule {
        processDefinitionIds = normalizeSet(processDefinitionIds);
        errorTypes = normalizeSet(errorTypes);
        httpStatusCodes = httpStatusCodes == null ? Set.of() : Set.copyOf(httpStatusCodes);
        messageContains = messageContains == null ? List.of() : messageContains.stream()
                .filter(StringUtils::hasText)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .toList();
    }

    public boolean matches(IncidentResolutionContext context, Map<String, Object> incident) {
        return matchesWorkflow(context)
                && matchesErrorType(incident)
                && matchesHttpStatus(incident)
                && matchesMessageContent(incident);
    }

    public IncidentResolutionDecision toDecision() {
        return switch (mode) {
            case BLOCKED -> IncidentResolutionDecision.blocked(reason, userFacingGuidance);
            case NO_ACTION -> IncidentResolutionDecision.noAction(reason, userFacingGuidance);
            case BY_PROCESS_INSTANCE, BY_INCIDENT_KEY -> new IncidentResolutionDecision(mode, reason, userFacingGuidance);
        };
    }

    private boolean matchesWorkflow(IncidentResolutionContext context) {
        if (processDefinitionIds.isEmpty()) {
            return true;
        }
        Set<String> involvedIds = context.involvedProcessDefinitionIds();
        if (involvedIds == null || involvedIds.isEmpty()) {
            return processDefinitionIds.contains(normalize(context.workflowProcessDefinitionId()));
        }
        return involvedIds.stream()
                .map(IncidentResolutionRule::normalize)
                .anyMatch(processDefinitionIds::contains);
    }

    private boolean matchesErrorType(Map<String, Object> incident) {
        if (errorTypes.isEmpty()) {
            return true;
        }
        String errorType = normalize(asString(incident.get("errorType")));
        return errorTypes.contains(errorType);
    }

    private boolean matchesHttpStatus(Map<String, Object> incident) {
        if (httpStatusCodes.isEmpty()) {
            return true;
        }
        Integer explicitStatus = asInteger(incident.get("httpStatus"));
        if (explicitStatus != null) {
            return httpStatusCodes.contains(explicitStatus);
        }

        String message = normalize(asString(incident.get("errorMessage")));
        if (!StringUtils.hasText(message)) {
            message = normalize(asString(incident.get("message")));
        }
        if (!StringUtils.hasText(message)) {
            return false;
        }

        Matcher matcher = HTTP_STATUS_PATTERN.matcher(message);
        while (matcher.find()) {
            int status = Integer.parseInt(matcher.group(1));
            if (httpStatusCodes.contains(status)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesMessageContent(Map<String, Object> incident) {
        if (messageContains.isEmpty()) {
            return true;
        }
        String haystack = normalize(asString(incident.get("errorMessage")))
                + " "
                + normalize(asString(incident.get("message")))
                + " "
                + normalize(asString(incident.get("processDefinitionId")));
        return messageContains.stream().anyMatch(haystack::contains);
    }

    private static Set<String> normalizeSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                normalized.add(normalize(value));
            }
        }
        return Set.copyOf(normalized);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
