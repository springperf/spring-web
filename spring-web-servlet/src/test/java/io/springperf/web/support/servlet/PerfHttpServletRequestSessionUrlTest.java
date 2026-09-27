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

    /**
     * 只认 URI <b>路径段</b>上的路径参数：query / fragment 里的 {@code ;jsessionid=} 不是会话标识。
     * <p>
     * 若从 query 里回读，攻击者可用 {@code /any?u=;jsessionid=<known-id>} 把已知 id 塞进受害者的会话解析 —— 标准会话固定向量。
     * </p>
     */
    @Test
    void ignoresParameterInQuery() {
        assertThat(PerfHttpServletRequest.parseSessionIdFromUri("/app/foo?q=;jsessionid=EVIL")).isNull();
        assertThat(PerfHttpServletRequest.parseSessionIdFromUri("/app/foo?a=1&b=;jsessionid=EVIL")).isNull();
        assertThat(PerfHttpServletRequest.parseSessionIdFromUri("/app/foo#;jsessionid=EVIL")).isNull();
        assertThat(PerfHttpServletRequest.parseSessionIdFromUri("/app/foo?jsessionid=EVIL")).isNull();
    }

    /** query 之前（含最后一个路径段）的路径参数照常解析：真实 URL 重写场景不受影响。 */
    @Test
    void stillParsesLastPathSegmentBeforeQuery() {
        assertThat(PerfHttpServletRequest.parseSessionIdFromUri("/app/foo;jsessionid=ABC123?q=1")).isEqualTo("ABC123");
        assertThat(PerfHttpServletRequest.parseSessionIdFromUri("/app;jsessionid=ABC123/foo?q=1")).isEqualTo("ABC123");
    }

    /** 空 query / 空 fragment 边界：剥离后不应产生空指针或下标越界。 */
    @Test
    void emptyQueryOrFragment_isTolerated() {
        assertThat(PerfHttpServletRequest.parseSessionIdFromUri("/a;jsessionid=X?")).isEqualTo("X");
        assertThat(PerfHttpServletRequest.parseSessionIdFromUri("/a;jsessionid=X#")).isEqualTo("X");
        assertThat(PerfHttpServletRequest.parseSessionIdFromUri("?;jsessionid=EVIL")).isNull();
        assertThat(PerfHttpServletRequest.parseSessionIdFromUri("#;jsessionid=EVIL")).isNull();
    }
}
