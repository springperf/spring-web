package io.springperf.example.batch.perf;

import io.springperf.web.batch.common.BatchRequest;

/**
 * 批量 IO 性能对照实验的请求容器。
 * <p>
 * 构造器签名 {@code (String id)} 必须与单请求方法的参数列表一致——{@code BatchScanner} 靠它把参数解析结果 装进 {@link BatchRequest}（见
 * {@code resolveSingleMethodCtor}）。
 * </p>
 */
public class PerfIoRequest extends BatchRequest<String> {

    private final String id;

    public PerfIoRequest(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
