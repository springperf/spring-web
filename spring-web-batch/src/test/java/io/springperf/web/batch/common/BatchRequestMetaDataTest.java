package io.springperf.web.batch.common;

import io.springperf.web.batch.annotation.BatchMapping;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;

class BatchRequestMetaDataTest {

    @Test
    void constructorAndAccessors() throws Exception {
        Constructor<?> ctor = String.class.getDeclaredConstructor(String.class);

        BatchRequestMetaData meta = new BatchRequestMetaData(
                Object.class.getDeclaredMethod("hashCode"),
                String.class,
                null,
                "test-queue",
                1024,
                BatchMapping.WaitStrategy.BLOCKING,
                BatchMapping.Backpressure.BLOCK,
                ctor,
                64,
                4
        );

        assertThat(meta.batchMethod()).isEqualTo(Object.class.getDeclaredMethod("hashCode"));
        assertThat(meta.beanType()).isEqualTo(String.class);
        assertThat(meta.requestType()).isNull();
        assertThat(meta.queueName()).isEqualTo("test-queue");
        assertThat(meta.ringBufferSize()).isEqualTo(1024);
        assertThat(meta.waitStrategy()).isEqualTo(BatchMapping.WaitStrategy.BLOCKING);
        assertThat(meta.backpressure()).isEqualTo(BatchMapping.Backpressure.BLOCK);
        assertThat(meta.singleMethodCtor()).isSameAs(ctor);
        assertThat(meta.maxBatchSize()).isEqualTo(64);
        assertThat(meta.consumerSize()).isEqualTo(4);
    }
}
