package com.personal.jmeter.listener;

import java.util.Objects;

/**
 * Scenario-level metadata passed to the AI report prompt.
 *
 * <p>Extracted from {@link AggregateReportPanel} to satisfy the 300-line class
 * design limit (Standard 3 SRP). This is a pure value object with no behaviour.</p>
 */
public final class ScenarioMetadata {
    /**
     * Test plan name.
     */
    public final String scenarioName;
    /**
     * Test plan description / comment.
     */
    public final String scenarioDesc;
    /**
     * Virtual user count label.
     */
    public final String users;
    /**
     * First thread group name.
     */
    public final String threadGroupName;

    /**
     * Constructs scenario metadata.
     *
     * @param scenarioName    test plan name (null → "")
     * @param scenarioDesc    test plan description (null → "")
     * @param users           virtual user count label (null → "")
     * @param threadGroupName first thread group name (null → "")
     */
    public ScenarioMetadata(String scenarioName, String scenarioDesc,
                            String users, String threadGroupName) {
        this.scenarioName = Objects.requireNonNullElse(scenarioName, "");
        this.scenarioDesc = Objects.requireNonNullElse(scenarioDesc, "");
        this.users = Objects.requireNonNullElse(users, "");
        this.threadGroupName = Objects.requireNonNullElse(threadGroupName, "");
    }

    /**
     * Returns an empty {@code ScenarioMetadata} instance.
     *
     * @return metadata with all fields empty
     */
    public static ScenarioMetadata empty() {
        return new ScenarioMetadata("", "", "", "");
    }
}
