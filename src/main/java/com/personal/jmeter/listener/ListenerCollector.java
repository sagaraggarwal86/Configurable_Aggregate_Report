package com.personal.jmeter.listener;

import org.apache.jmeter.reporters.ResultCollector;

/**
 * Backend collector for the Configurable Aggregate Report plugin.
 *
 * <p>Persists three configuration properties into the .jmx file only.
 * No live metric collection — all data comes from uploaded JTL files.</p>
 */
public class ListenerCollector extends ResultCollector {

    /**
     * JMeter property key for the start-offset filter (seconds).
     */
    public static final String PROP_START_OFFSET = "startOffset";
    /**
     * JMeter property key for the end-offset filter (seconds).
     */
    public static final String PROP_END_OFFSET = "endOffset";
    /**
     * JMeter property key for the configured percentile.
     */
    public static final String PROP_PERCENTILE = "percentile";
    @java.io.Serial
    private static final long serialVersionUID = 1L;
}
