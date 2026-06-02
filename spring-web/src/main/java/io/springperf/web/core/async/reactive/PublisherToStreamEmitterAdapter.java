package io.springperf.web.core.async.reactive;

import io.springperf.web.core.async.stream.StreamEmitter;
import io.springperf.web.core.async.stream.StreamSender;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.springframework.core.ReactiveAdapter;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.io.IOException;

@Slf4j
public class PublisherToStreamEmitterAdapter implements Subscriber<Object> {

    private final StreamEmitter emitter;

    private final StreamSender sender;

    private final ReactiveConfig config;

    private Subscription subscription;

    private volatile boolean terminated = false;


    public PublisherToStreamEmitterAdapter(StreamEmitter emitter, StreamSender sender, ReactiveConfig config) {
        this.emitter = emitter;
        this.sender = sender;
        this.config = config;
    }

    public void subscribe(ReactiveAdapter adapter, Object returnValue) {
        Publisher<Object> publisher = adapter.toPublisher(returnValue);
        publisher.subscribe(this);
    }

    @Override
    public void onSubscribe(Subscription s) {
        this.subscription = s;
        // 绑定 emitter 生命周期
        emitter.onTimeout(() -> tryCancel(new AsyncRequestTimeoutException()));
        emitter.onWriteCallback(t -> {
            if (t == null) {
                tryRequest();
            } else {
                tryCancel((Throwable) t);
            }
        });
        subscription.request(config.getHighWaterMark());
    }

    protected void tryRequest() {
        if (terminated) {
            return;
        }
        if (sender.queueSize() < config.getLowWaterMark()) {
            subscription.request(config.getHighWaterMark() - sender.queueSize());
        }
    }

    protected void tryCancel(Throwable e) {
        if (terminated) {
            return;
        }
        log.error("send data error", e);
        subscription.cancel();
        onError(e);
    }

    @Override
    public void onNext(Object o) {
        try {
            sender.send(o);
        } catch (IOException e) {
            tryCancel(e);
        }
    }

    @Override
    public void onError(Throwable t) {
        if (terminated) {
            return;
        }
        terminated = true;
        emitter.completeWithError(t);
    }

    @Override
    public void onComplete() {
        if (terminated) {
            return;
        }
        terminated = true;
        emitter.complete();
    }
}
