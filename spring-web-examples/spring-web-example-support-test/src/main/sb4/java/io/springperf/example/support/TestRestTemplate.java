package io.springperf.example.support;

/**
 * 跨版本测试用 REST 客户端（Spring Boot 4 版）。
 * <p>
 * Boot 4 把 {@code TestRestTemplate} 移到 {@code org.springframework.boot.resttestclient}
 * （artifact：{@code spring-boot-resttestclient}），{@code RestTemplateBuilder} 移到
 * {@code org.springframework.boot.restclient}（artifact：{@code spring-boot-restclient}）。
 * 与 SB3 版同名同包，由 profile 选择本 source root。详见 SB3 版 Javadoc。
 * </p>
 */
public class TestRestTemplate extends org.springframework.boot.resttestclient.TestRestTemplate {

    /** 接收本 shim 的 builder（内部 unwrap 成该版本真实的 {@code RestTemplateBuilder}）。 */
    public TestRestTemplate(RestTemplateBuilder builder) {
        super(builder.unwrap());
    }

    /** 无参（= varargs 空参）：两版本都提供 {@code TestRestTemplate(HttpClientOption...)}。 */
    public TestRestTemplate() {
        super();
    }
}
