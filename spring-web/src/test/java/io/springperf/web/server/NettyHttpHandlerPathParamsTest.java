package io.springperf.web.server;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 矩阵参数（path parameter）剥离：路由匹配用剥离后的路径（对齐 Spring
 * {@code UrlPathHelper.removeSemicolonContent=true}），否则服务端自己写出的
 * {@code ;jsessionid=} URL 打不开（404）。
 */
class NettyHttpHandlerPathParamsTest {

    @Test
    void pathWithoutSemicolon_returnedAsIs() {
        String path = "/orders/42";
        assertThat(NettyHttpHandler.stripPathParams(path)).isSameAs(path);
    }

    @Test
    void singleSegmentParam_stripped() {
        assertThat(NettyHttpHandler.stripPathParams("/foo;jsessionid=ABC")).isEqualTo("/foo");
        assertThat(NettyHttpHandler.stripPathParams("/foo;a=b/bar")).isEqualTo("/foo/bar");
    }

    @Test
    void paramInLaterSegment_onlyThatSegmentStripped() {
        assertThat(NettyHttpHandler.stripPathParams("/a;x=1/b;jsessionid=ABC/c"))
                .isEqualTo("/a/b/c");
    }

    @Test
    void trailingEmptySegment_keepsSlashBoundary() {
        // 末段带参数：只丢弃该段 ';' 之后内容，前导 '/' 保留
        assertThat(NettyHttpHandler.stripPathParams("/a/b;jsessionid=ABC")).isEqualTo("/a/b");
        assertThat(NettyHttpHandler.stripPathParams("/;jsessionid=ABC")).isEqualTo("/");
    }

    @Test
    void nullPath_returnsNull() {
        assertThat(NettyHttpHandler.stripPathParams(null)).isNull();
    }
}
