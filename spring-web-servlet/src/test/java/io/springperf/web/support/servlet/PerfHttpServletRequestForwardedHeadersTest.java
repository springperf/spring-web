package io.springperf.web.support.servlet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;

/**
 * 转发头信任判定：本类的判定必须与 {@code NettyServerHttpRequest#isForwardedHeadersEnabled} 逐字符一致， 否则「会话 Cookie 是否带
 * Secure」会随调用点不同而翻转（两侧都读同一个 {@code server.forward-headers-strategy}）。
 * <p>
 * 关键回归点：{@code trim()}。Netty 侧是 trim 后比较的，本侧若不 trim，配置写成 {@code " none "} 时 本侧判为信任、Netty 侧判为不信任 —— 一个只有空格之差的输入改变了安全结论。
 * </p>
 */
class PerfHttpServletRequestForwardedHeadersTest {

    private static boolean trusted(String strategy) {
        WebContext webContext = mock(WebContext.class);
        ApplicationProperties props = mock(ApplicationProperties.class);
        lenient().when(webContext.getProps()).thenReturn(props);
        lenient().when(props.get(PropertiesConstant.FORWARD_HEADERS_STRATEGY, null)).thenReturn(strategy);
        return PerfHttpServletRequest.isForwardedHeadersTrusted(webContext);
    }

    @Test
    void unsetOrBlank_isNotTrusted() {
        assertFalse(trusted(null), "未配置 = 不信任（安全默认值）");
        assertFalse(trusted(""));
        assertFalse(trusted("   "), "全空白 = 不信任");
    }

    @Test
    void noneOrFalse_isNotTrusted() {
        assertFalse(trusted("NONE"));
        assertFalse(trusted("none"), "大小写不敏感");
        assertFalse(trusted("FALSE"));
        assertFalse(trusted("false"));
    }

    /** 回归：修复前不 trim，带空白的 NONE/FALSE 会被误判为信任。 */
    @Test
    void noneOrFalseWithSurroundingWhitespace_isNotTrusted() {
        assertFalse(trusted(" none "), "必须 trim 后才比较，否则与 Netty 侧结论相反");
        assertFalse(trusted("\tNONE\n"));
        assertFalse(trusted(" false "));
    }

    @Test
    void explicitStrategy_isTrusted() {
        assertTrue(trusted("NATIVE"));
        assertTrue(trusted("FRAMEWORK"));
    }

    @Test
    void explicitStrategyWithWhitespace_isTrusted() {
        assertTrue(trusted(" native "), "trim 后是有效策略，应信任");
    }

    @Test
    void missingWebContext_isNotTrusted() {
        assertFalse(PerfHttpServletRequest.isForwardedHeadersTrusted(null), "取不到 WebContext 时按不信任处理");
    }
}
