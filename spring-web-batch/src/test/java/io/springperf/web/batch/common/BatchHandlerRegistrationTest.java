package io.springperf.web.batch.common;

import io.springperf.web.core.mapping.PathMappingContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BatchHandlerRegistrationTest {

    @Test
    void constructorAndAccessors() {
        Object bean = new Object();
        PathMappingContext ctx = mock(PathMappingContext.class);
        BatchRequestMetaData meta = mock(BatchRequestMetaData.class);

        BatchHandlerRegistration reg = new BatchHandlerRegistration(bean, ctx, meta);

        assertThat(reg.bean()).isSameAs(bean);
        assertThat(reg.singleCtx()).isSameAs(ctx);
        assertThat(reg.meta()).isSameAs(meta);
    }
}
