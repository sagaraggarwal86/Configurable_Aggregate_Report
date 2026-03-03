package com.Sagar.jmeter.listener;

import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SamplePluginListener extends AbstractTestElement implements SampleListener {

    private static final Logger log = LoggerFactory.getLogger(SamplePluginListener.class);

    @Override
    public void sampleOccurred(SampleEvent event) {
        SampleResult r = event.getResult();
        log.info("[SamplePlugin] {} | Success={} | {}ms",
                r.getSampleLabel(), r.isSuccessful(), r.getTime());
    }

    @Override public void sampleStarted(SampleEvent e) {}
    @Override public void sampleStopped(SampleEvent e) {}
}