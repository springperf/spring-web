package io.springperf.web.core.async.reactive;

import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.springframework.core.ReactiveAdapter;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class PublisherToDeferredResultAdapter implements Subscriber<Object> {

    private final DeferredResult result;

    private final boolean multiValueSource;
    private final List valueList = new ArrayList<>();
    private Subscription subscription;

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
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(Object o) {
        valueList.add(o);
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
