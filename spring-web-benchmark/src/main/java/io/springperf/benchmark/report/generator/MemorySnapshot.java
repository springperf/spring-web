package io.springperf.benchmark.report.generator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 内存快照数据模型。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemorySnapshot {

    private String profile;
    @JsonProperty("jdk_version")
    private String jdkVersion;
    private Map<String, Object> heap;
    @JsonProperty("non_heap")
    private Map<String, Object> nonHeap;

    public String getProfile() { return profile; }
    public String getJdkVersion() { return jdkVersion; }
    public Map<String, Object> getHeap() { return heap; }
    public Map<String, Object> getNonHeap() { return nonHeap; }

    public void setProfile(String v) { this.profile = v; }
    public void setJdkVersion(String v) { this.jdkVersion = v; }
    public void setHeap(Map<String, Object> v) { this.heap = v; }
    public void setNonHeap(Map<String, Object> v) { this.nonHeap = v; }

    public String getHeapUsedMb() {
        if (heap == null) return "N/A";
        Object used = heap.get("used");
        if (used instanceof Number) {
            return String.format("%.0fMB", ((Number) used).doubleValue() / 1024 / 1024);
        }
        return "N/A";
    }
}