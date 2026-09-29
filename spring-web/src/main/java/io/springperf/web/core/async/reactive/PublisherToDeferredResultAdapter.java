package io.springperf.web.core.async.reactive;

import java.util.ArrayList;
import java.util.List;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.springframework.core.ReactiveAdapter;
import org.springframework.web.context.request.async.DeferredResult;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PublisherToDeferredResultAdapter implements Subscriber<Object> {

    /**
     * 每次向 Publisher 请求的元素的数量。以分批代替 {@code request(Long.MAX_VALUE)}，让上游遵守背压：
     * 无界请求会让上游一次性把所有元素推入 {@link #valueList}，失去流量控制。
     */
    public static final int REQUEST_BATCH_SIZE = 32;

    private final DeferredResult result;

    private final boolean multiValueSource;
    private final List valueList = new ArrayList<>();
    private Subscription subscription;
    /** 已交付但尚未向 Publisher 补请求的元素数。归零后立即补下一批。 */
    private int pendingRequest;

    public PublisherToDeferredResultAdapter(DeferredResult<?> result, ReactiveAdapter adapter) {
        this.result = result;
        this.multiValueSource = adapter.isMultiValue();
    }

    public void subscribe(ReactiveAdapter adapter, Object returnValue) {
        Publisher<Object> publisher = adapter.toPublisher(returnValue);
        publisher.subscribe(this);
    }

    @Override
    public void onSubscribe(Subscription s) {
        this.subscription = s;
        result.onTimeout(subscription::cancel);
        pendingRequest = REQUEST_BATCH_SIZE;
        s.request(REQUEST_BATCH_SIZE);
    }

    @Override
    public void onNext(Object o) {
        valueList.add(o);
        // Reactive Streams 保证 onNext 串行调用，故以下计数无需同步。
        if (--pendingRequest == 0) {
            pendingRequest = REQUEST_BATCH_SIZE;
            subscription.request(REQUEST_BATCH_SIZE);
        }
    }

    @Override
    public void onError(Throwable t) {
        result.setErrorResult(t);
    }

    @Override
    public void onComplete() {
        if (this.valueList.size() > 1 || this.multiValueSource) {
            this.result.setResult(this.valueList);
        } else if (this.valueList.size() == 1) {
            this.result.setResult(this.valueList.get(0));
        } else {
            this.result.setResult(null);
        }
    }
}
