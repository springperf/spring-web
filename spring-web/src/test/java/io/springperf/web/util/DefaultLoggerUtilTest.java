package io.springperf.web.util;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static org.junit.jupiter.api.Assertions.*;

class DefaultLoggerUtilTest {

    @Test
    void log_isNotNull() {
        assertNotNull(DefaultLoggerUtil.log);
    }

    @Test
    void log_isSlf4jLogger() {
        // 本模块测试类路径没有 slf4j 绑定，实际拿到的是 NOPLogger，无法断言 logger 名称/级别
        assertTrue(DefaultLoggerUtil.log instanceof Logger);
    }

    @Test
    void log_isWritable() {
        assertDoesNotThrow(() -> DefaultLoggerUtil.log.info("test message"));
        assertDoesNotThrow(() -> DefaultLoggerUtil.log.warn("test warning"));
        assertDoesNotThrow(() -> DefaultLoggerUtil.log.error("test error"));
    }
}