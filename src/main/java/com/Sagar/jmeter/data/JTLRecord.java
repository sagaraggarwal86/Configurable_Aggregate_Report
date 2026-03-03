package com.Sagar.jmeter.data;

/**
 * Represents a single row from a JTL file
 */
public class JTLRecord {
    private long timeStamp;
    private long elapsed;
    private String label;
    private String responseCode;
    private String responseMessage;
    private String threadName;
    private String dataType;
    private boolean success;
    private String failureMessage;
    private long bytes;
    private long sentBytes;
    private int grpThreads;
    private int allThreads;
    private String url;
    private long latency;
    private long idleTime;
    private long connect;

    public long getTimeStamp() { return timeStamp; }
    public void setTimeStamp(long timeStamp) { this.timeStamp = timeStamp; }

    public long getElapsed() { return elapsed; }
    public void setElapsed(long elapsed) { this.elapsed = elapsed; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getResponseCode() { return responseCode; }
    public void setResponseCode(String responseCode) { this.responseCode = responseCode; }

    public String getResponseMessage() { return responseMessage; }
    public void setResponseMessage(String responseMessage) { this.responseMessage = responseMessage; }

    public String getThreadName() { return threadName; }
    public void setThreadName(String threadName) { this.threadName = threadName; }

    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }

    public long getBytes() { return bytes; }
    public void setBytes(long bytes) { this.bytes = bytes; }

    public long getSentBytes() { return sentBytes; }
    public void setSentBytes(long sentBytes) { this.sentBytes = sentBytes; }

    public int getGrpThreads() { return grpThreads; }
    public void setGrpThreads(int grpThreads) { this.grpThreads = grpThreads; }

    public int getAllThreads() { return allThreads; }
    public void setAllThreads(int allThreads) { this.allThreads = allThreads; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public long getLatency() { return latency; }
    public void setLatency(long latency) { this.latency = latency; }

    public long getIdleTime() { return idleTime; }
    public void setIdleTime(long idleTime) { this.idleTime = idleTime; }

    public long getConnect() { return connect; }
    public void setConnect(long connect) { this.connect = connect; }
}
