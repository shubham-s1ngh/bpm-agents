package com.shubham.dev.bpm_agent.chat.validation;

import com.shubham.dev.bpm_agent.chat.model.CamundaEvidenceSnapshot;
import com.shubham.dev.bpm_agent.chat.model.CamundaInstanceEvidence;
import com.shubham.dev.bpm_agent.chat.service.CamundaEvidenceDigestService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates that a generated report is fully grounded in Camunda evidence.
 *
 * <p>The validator rejects unsupported identifiers, contradictory state claims,
 * incorrect incident summaries, and leaked tool-call JSON. It also provides a
 * sanitize fallback so unsupported identifiers are neutralized instead of being
 * returned as fabricated data.</p>
 */
@Service
public class CamundaReportGroundingValidator {

    private final CamundaEvidenceDigestService evidenceDigestService;

    public CamundaReportGroundingValidator(CamundaEvidenceDigestService evidenceDigestService) {
        this.evidenceDigestService = evidenceDigestService;
    }

    public List<String> validate(String generatedReport, CamundaEvidenceSnapshot snapshot) {
        List<String> errors = new ArrayList<>();

        Matcher numberMatcher = Pattern.compile("\\b\\d{8,}\\b").matcher(generatedReport);
        while (numberMatcher.find()) {
            String number = numberMatcher.group();
            if (!snapshot.allowedNumbers().contains(number)) {
                errors.add("Rejected numeric identifier not present in Camunda payload: " + number);
            }
        }

        Matcher processLikeMatcher = Pattern.compile("\\b[A-Za-z][A-Za-z0-9]*_[A-Za-z0-9_]+\\b").matcher(generatedReport);
        while (processLikeMatcher.find()) {
            String identifier = processLikeMatcher.group();
            if (!snapshot.allowedProcessLikeIdentifiers().contains(identifier)) {
                errors.add("Rejected process-like identifier not present in Camunda payload: " + identifier);
            }
        }

        if (evidenceDigestService.hasAnyIncidents(snapshot)
                && Pattern.compile("(?i)no active incidents|active incidents:\\s*none").matcher(generatedReport).find()) {
            errors.add("Rejected incident summary contradicts Camunda payload: report says no incidents but evidence contains incidents.");
        }

        if (Pattern.compile("(?s)\\{\\s*\"name\"\\s*:\\s*\"(searchProcessInstances|fetchVariablesForInstance|diagnoseProcessInstance|resolveIncidentByKey|resolveIncidentsByProcessInstance)\"")
                .matcher(generatedReport)
                .find()) {
            errors.add("Rejected tool-call JSON in final report.");
        }

        validatePerInstanceBlocks(generatedReport, snapshot, errors);
        return errors;
    }

    public String sanitize(String generatedReport, CamundaEvidenceSnapshot snapshot) {
        String sanitized = generatedReport;

        Matcher numberMatcher = Pattern.compile("\\b\\d{8,}\\b").matcher(sanitized);
        StringBuffer numberBuffer = new StringBuffer();
        while (numberMatcher.find()) {
            String number = numberMatcher.group();
            String replacement = snapshot.allowedNumbers().contains(number) ? number : "[not returned by Camunda]";
            numberMatcher.appendReplacement(numberBuffer, Matcher.quoteReplacement(replacement));
        }
        numberMatcher.appendTail(numberBuffer);
        sanitized = numberBuffer.toString();

        Matcher processLikeMatcher = Pattern.compile("\\b[A-Za-z][A-Za-z0-9]*_[A-Za-z0-9_]+\\b").matcher(sanitized);
        StringBuffer identifierBuffer = new StringBuffer();
        while (processLikeMatcher.find()) {
            String identifier = processLikeMatcher.group();
            String replacement = snapshot.allowedProcessLikeIdentifiers().contains(identifier) ? identifier : "[not returned by Camunda]";
            processLikeMatcher.appendReplacement(identifierBuffer, Matcher.quoteReplacement(replacement));
        }
        processLikeMatcher.appendTail(identifierBuffer);
        sanitized = identifierBuffer.toString();

        if (evidenceDigestService.hasAnyIncidents(snapshot)) {
            sanitized = sanitized.replaceAll("(?i)#### Active Incidents:\\s*None", "#### Active Incidents: Report text contradicted Camunda evidence");
            sanitized = sanitized.replaceAll("(?i)No active incidents found\\.", "Camunda returned active incidents; see evidence-backed incident details.");
        }

        for (CamundaInstanceEvidence instance : snapshot.instancesByKey().values()) {
            if (!StringUtils.hasText(instance.key()) || !StringUtils.hasText(instance.state())) {
                continue;
            }
            sanitized = sanitized.replaceAll(
                    "(?s)(Process Instance Key:\\s*" + Pattern.quote(instance.key()) + ".*?\\*\\*State:\\*\\*\\s*)(ACTIVE|COMPLETED|TERMINATED)",
                    "$1" + instance.state()
            );
        }

        return sanitized;
    }

    private void validatePerInstanceBlocks(String generatedReport,
                                           CamundaEvidenceSnapshot snapshot,
                                           List<String> errors) {
        Pattern keyPattern = Pattern.compile("\\b\\d{8,}\\b");
        Matcher matcher = keyPattern.matcher(generatedReport);
        while (matcher.find()) {
            String key = matcher.group();
            CamundaInstanceEvidence evidence = snapshot.instancesByKey().get(key);
            if (evidence == null) {
                continue;
            }

            int blockStart = matcher.start();
            int nextHeading = generatedReport.indexOf("###", blockStart + 3);
            String block = generatedReport.substring(blockStart, nextHeading >= 0 ? nextHeading : generatedReport.length());

            if (StringUtils.hasText(evidence.state())) {
                if (!block.contains(evidence.state())) {
                    errors.add("Rejected state mismatch for processInstanceKey " + key + ": expected state " + evidence.state());
                }

                for (String stateCandidate : List.of("ACTIVE", "COMPLETED", "TERMINATED")) {
                    if (!stateCandidate.equals(evidence.state()) && block.contains(stateCandidate)) {
                        errors.add("Rejected contradictory state for processInstanceKey " + key + ": found " + stateCandidate + " but expected " + evidence.state());
                    }
                }
            }

            if (StringUtils.hasText(evidence.processDefinitionId())
                    && block.contains("Process Definition ID:")
                    && !block.contains(evidence.processDefinitionId())) {
                errors.add("Rejected process definition mismatch for processInstanceKey " + key + ": expected " + evidence.processDefinitionId());
            }

            if (evidence.incidentCount() > 0 && Pattern.compile("(?i)active incidents:\\s*none|no active incidents").matcher(block).find()) {
                errors.add("Rejected incident mismatch for processInstanceKey " + key + ": evidence contains incidents.");
            }
        }
    }
}
