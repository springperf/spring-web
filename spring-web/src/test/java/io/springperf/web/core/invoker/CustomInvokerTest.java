package io.springperf.web.core.invoker;

import io.springperf.web.core.mapping.match.Matcher;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CustomInvokerTest {

    @Test
    void getMatchers_defaultReturnsEmptyList() {
        // 验证接口默认契约：未覆写时返回空 Matcher 列表（供路由预过滤使用）
        CustomInvoker invoker = new CustomInvoker() {
            @Override
            public Method getHandleMethod() {
                return getClass().getDeclaredMethods()[0];
            }

            @Override
            public String getType() {
                return "test";
            }

            @Override
            public Object invoke(Object[] args) {
                return null;
            }
        };

        List<Matcher> matchers = invoker.getMatchers();
        assertNotNull(matchers);
        assertTrue(matchers.isEmpty());
    }

    @Test
    void defaultsDoNotThrow() {
        // 默认方法（getMatchers）在空实现下可用且不抛异常
        CustomInvoker invoker = new TestCustomInvoker();
        assertNotNull(invoker);
        assertDoesNotThrow(() -> invoker.getMatchers());
    }

    static class TestCustomInvoker implements CustomInvoker {
        @Override
        public Method getHandleMethod() {
            return getClass().getDeclaredMethods()[0];
        }

        @Override
        public String getType() {
            return "test";
        }

        @Override
        public Object invoke(Object[] args) {
            return null;
        }
    }
}