package io.springperf.web.core.async.reactive;

import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.core.async.stream.StreamEmitter;
import io.springperf.web.core.async.stream.StreamSender;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.springframework.core.ReactiveAdapter;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.io.IOException;
import java.util.function.Consumer;

@Slf4j
public class PublisherToStreamEmitterAdapter implements Subscriber<Object> {

    private final StreamEmitter emitter;

    private final StreamSender sender;

    private final ReactiveConfig config;

    private final PerfAsyncWebRequest asyncWebRequest;

    private Subscription subscription;

    private volatile boolean terminated = false;


    public PublisherToStreamEmitterAdapter(StreamEmitter emitter, StreamSender sender, ReactiveConfig config,
                                           PerfAsyncWebRequest asyncWebRequest) {
        this.emitter = emitter;
        this.sender = sender;
        this.config = config;
        this.asyncWebRequest = asyncWebRequest;
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
        Consumer<Throwable> writeCallback = t -> {
            if (t == null) {
                tryRequest();
            } else {
                tryCancel((Throwable) t);
            }
        };
        emitter.onWriteCallback(writeCallback);
        // 订阅建立（onSubscribe）后立即把写回调同步给 asyncWebRequest：写完成 → 背压补充请求。
        // 修复前在 resolveReturnValue 的 subscribe() 返回后事后读取 emitter.getWriteCallbackHandler()；
        // 对 onSubscribe 异步投递的 Publisher（publishOn/subscribeOn 边界、第三方实现），该值
        // 此时仍为 null，addWriteCallbackHandler(null) 使写完成永不触发补充请求，
        // 遵守背压的冷 Publisher 在 highWaterMark 条后流永久停滞。此处无论同步/异步 onSubscribe
        // 都在订阅建立后立即注册，写帧必然发生在 onSubscribe 之后，回调始终可用。
        if (asyncWebRequest != null) {
            asyncWebRequest.addWriteCallbackHandler(writeCallback);
        }
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
