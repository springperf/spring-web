package io.springperf.web.support.servlet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * URL 重写回读：从请求 URI 解析 {@code ;jsessionid=<id>}（Servlet 规范 §7.1）。
 * <p>
 * 这是 {@code tracking-modes=URL} 的**读侧**——只有写出没有回读，会让跨请求会话静默丢失。
 * </p>
 */
class PerfHttpServletRequestSessionUrlTest {

    @Test
    void parsesIdFromPath() {
        assertThat(PerfHttpServletRequest.parseSessionIdFromUri("/app/foo;jsessionid=ABC123")).isEqualTo("ABC123");
    }

    @Test
    void valueStopsAtPathSeparatorOrQueryOrFragment() {
        assertThat(PerfHttpServletRequest.parseSessionIdFromUri("/a;jsessionid=X/b")).isEqualTo("X");
        assertThat(PerfHttpServletRequest.parseSessionIdFromUri("/a;jsessionid=X?q=1")).isEqualTo("X");
        assertThat(PerfHttpServletRequest.parseSessionIdFromUri("/a;jsessionid=X#frag")).isEqualTo("X");
        assertThat(PerfHttpServletRequest.parseSessionIdFromUri("/a;jsessionid=X;y=1")).isEqualTo("X");
    }

    @Test
    void caseInsensitiveParameterName() {
        assertThat(PerfHttpServletRequest.parseSessionIdFromUri("/a;JSESSIONID=X")).isEqualTo("X");
    }

    @Test
    void absentOrEmpty_returnsNull() {
        assertThat(PerfHttpServletRequest.parseSessionIdFromUri("/a/b")).isNull();
        assertThat(PerfHttpServletRequest.parseSessionIdFromUri("/a;jsessionid=")).isNull();
        assertThat(PerfHttpServletRequest.parseSessionIdFromUri(null)).isNull();
    }
}
