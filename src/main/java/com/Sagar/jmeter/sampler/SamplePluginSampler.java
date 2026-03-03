package com.Sagar.jmeter.sampler;

import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SamplePluginSampler extends AbstractSampler {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(SamplePluginSampler.class);

    // Keys used to store properties (shown in GUI)
    public static final String NAME            = "SamplePlugin.name";
    public static final String FILE_NAME       = "SamplePlugin.fileName";
    public static final String FILTER_SETTINGS = "SamplePlugin.filterSettings";
    public static final String START           = "SamplePlugin.start";
    public static final String DURATION        = "SamplePlugin.duration";

    @Override
    public SampleResult sample(Entry entry) {
        SampleResult result = new SampleResult();
        result.setSampleLabel(getName());
        result.setDataType(SampleResult.TEXT);

        String name           = getName();
        String fileName       = getFileName();
        String filterSettings = getFilterSettings();
        String start          = getStart();
        String duration       = getDuration();

        log.info("SamplePlugin → Name: {}, FileName: {}, FilterSettings: {}, Start: {}, Duration: {}",
                name, fileName, filterSettings, start, duration);

        result.sampleStart(); // ← start measuring time

        try {
            // 👇 Replace this with your real logic:
            //    e.g., call a custom API, message queue, database, etc.
            Thread.sleep(50); // simulate processing

            String response = String.format(
                    "Plugin OK!\nName: %s\nFileName: %s\nFilterSettings: %s\nStart: %s\nDuration: %s",
                    name, fileName, filterSettings, start, duration
            );

            result.sampleEnd();
            result.setSuccessful(true);
            result.setResponseCodeOK();
            result.setResponseMessage("OK");
            result.setResponseData(response, "UTF-8");

        } catch (InterruptedException e) {
            result.sampleEnd();
            result.setSuccessful(false);
            result.setResponseCode("500");
            result.setResponseMessage("Interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            result.sampleEnd();
            result.setSuccessful(false);
            result.setResponseCode("500");
            result.setResponseMessage("Error: " + e.getMessage());
            log.error("SamplePlugin error", e);
        }

        return result;
    }

    // Getters & setters bound to JMeter's property store
    public String getName()           { return getPropertyAsString(NAME, ""); }
    public void   setName(String v)   { setProperty(NAME, v); }

    public String getFileName()       { return getPropertyAsString(FILE_NAME, ""); }
    public void   setFileName(String v) { setProperty(FILE_NAME, v); }

    public String getFilterSettings() { return getPropertyAsString(FILTER_SETTINGS, ""); }
    public void   setFilterSettings(String v) { setProperty(FILTER_SETTINGS, v); }

    public String getStart()          { return getPropertyAsString(START, ""); }
    public void   setStart(String v)  { setProperty(START, v); }

    public String getDuration()       { return getPropertyAsString(DURATION, ""); }
    public void   setDuration(String v) { setProperty(DURATION, v); }
}
