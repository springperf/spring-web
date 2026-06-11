package io.springperf.benchmark.report.generator;

/**
 * GC 日志解析结果模型。
 */
public class GcMetrics {

    private int youngGcCount;
    private double youngGcTotalMs;
    private double youngGcMaxMs;
    private double youngGcAvgMs;
    private int fullGcCount;
    private double fullGcTotalMs;
    private double fullGcMaxMs;

    public int getYoungGcCount() { return youngGcCount; }
    public double getYoungGcTotalMs() { return youngGcTotalMs; }
    public double getYoungGcMaxMs() { return youngGcMaxMs; }
    public double getYoungGcAvgMs() { return youngGcAvgMs; }
    public int getFullGcCount() { return fullGcCount; }
    public double getFullGcTotalMs() { return fullGcTotalMs; }
    public double getFullGcMaxMs() { return fullGcMaxMs; }

    public GcMetrics setYoungGcCount(int v) { this.youngGcCount = v; return this; }
    public GcMetrics setYoungGcTotalMs(double v) { this.youngGcTotalMs = v; return this; }
    public GcMetrics setYoungGcMaxMs(double v) { this.youngGcMaxMs = v; return this; }
    public GcMetrics setYoungGcAvgMs(double v) { this.youngGcAvgMs = v; return this; }
    public GcMetrics setFullGcCount(int v) { this.fullGcCount = v; return this; }
    public GcMetrics setFullGcTotalMs(double v) { this.fullGcTotalMs = v; return this; }
    public GcMetrics setFullGcMaxMs(double v) { this.fullGcMaxMs = v; return this; }
}