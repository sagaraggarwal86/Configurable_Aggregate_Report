package com.personal.jmeter.ai;

import java.util.Objects;

/**
 * Parameter object grouping scenario-level context for an AI prompt.
 *
 * <p>Introduced to keep {@link PromptBuilder#build} within the three-parameter limit
 * required by the method design standard.</p>
 *
 * @param users        virtual user count label (blank if unknown)
 * @param scenarioName test plan name (blank if unknown)
 * @param scenarioDesc test plan comment / description (blank if unknown)
 * @param startTime    formatted test start time (blank if unknown)
 * @param duration     formatted test duration (blank if unknown)
 */
public record PromptRequest(
        String users,
        String scenarioName,
        String scenarioDesc,
        String startTime,
        String duration) {

    /**
     * Compact canonical constructor — normalises null fields to empty strings.
     */
    public PromptRequest {
        users = Objects.requireNonNullElse(users, "");
        scenarioName = Objects.requireNonNullElse(scenarioName, "");
        scenarioDesc = Objects.requireNonNullElse(scenarioDesc, "");
        startTime = Objects.requireNonNullElse(startTime, "");
        duration = Objects.requireNonNullElse(duration, "");
    }

    /**
     * Returns a {@code PromptRequest} with all fields empty.
     *
     * @return empty request instance
     */
    public static PromptRequest empty() {
        return new PromptRequest("", "", "", "", "");
    }
}
