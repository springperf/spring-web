package io.springperf.web.batch.invoker;

import io.springperf.web.batch.common.BatchRequest;
import io.springperf.web.batch.common.BatchRequestMetaData;
import io.springperf.web.batch.queue.DisruptorQueue;
import io.springperf.web.core.invoker.CustomInvoker;
import io.springperf.web.core.mapping.match.Matcher;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

public class BatchInvoker implements CustomInvoker {

    private final BatchRequestMetaData meta;
    private final DisruptorQueue queue;
    private final Method handleMethod;

    public BatchInvoker(BatchRequestMetaData meta, DisruptorQueue queue) {
        this.meta = meta;
        this.queue = queue;
        this.handleMethod = meta.batchMethod();
    }

    @Override
    public Object invoke(Object[] args) throws Exception {
        BatchRequest<?> batchReq = (BatchRequest<?>) meta.singleMethodCtor().newInstance(args);
        queue.enqueue(batchReq);
        return batchReq;
    }

    @Override
    public Method getHandleMethod() {
        return handleMethod;
    }

    @Override
    public String getType() {
        return "batch";
    }

    @Override
    public List<Matcher> getMatchers() {
        return Collections.emptyList();
    }
}
