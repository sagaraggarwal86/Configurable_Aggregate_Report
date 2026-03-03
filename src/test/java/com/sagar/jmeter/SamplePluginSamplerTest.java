package com.sagar.jmeter;

import com.Sagar.jmeter.sampler.SamplePluginSampler;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SamplePluginSamplerTest {

    private SamplePluginSampler sampler;

    @BeforeEach
    void setUp() {
        sampler = new SamplePluginSampler();
        sampler.setName("Test Sampler");
    }

    @Test
    void testDefaultValues() {
        assertEquals("", sampler.getFileName());
        assertEquals("", sampler.getFilterSettings());
        assertEquals("", sampler.getStart());
        assertEquals("", sampler.getDuration());
    }

    @Test
    void testSetFileName() {
        sampler.setFileName("/path/to/results.jtl");
        assertEquals("/path/to/results.jtl", sampler.getFileName());
    }

    @Test
    void testSetFilterSettings() {
        sampler.setFilterSettings("start=0;include=HTTP;includeRegExp=false");
        assertEquals("start=0;include=HTTP;includeRegExp=false", sampler.getFilterSettings());
    }

    @Test
    void testSetStartAndDuration() {
        sampler.setStart("0");
        sampler.setDuration("60");
        assertEquals("0", sampler.getStart());
        assertEquals("60", sampler.getDuration());
    }

    @Test
    void testSampleReturnsSuccess() {
        SampleResult result = sampler.sample(null);
        assertNotNull(result);
        assertTrue(result.isSuccessful());
        assertEquals("200", result.getResponseCode());
    }

    @Test
    void testSampleLabelMatchesName() {
        sampler.setName("My Sampler");
        assertEquals("My Sampler", sampler.sample(null).getSampleLabel());
    }

    @Test
    void testResponseContainsFileName() {
        sampler.setFileName("test-results.jtl");
        String response = new String(sampler.sample(null).getResponseData());
        assertTrue(response.contains("test-results.jtl"));
    }
}