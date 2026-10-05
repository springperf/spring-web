package io.springperf.example.support;

/**
 * 跨版本测试用 REST 客户端。
 * <p>
 * Spring Boot 3 的 {@code TestRestTemplate} 在 {@code org.springframework.boot.test.web.client}，
 * Boot 4 移到 {@code org.springframework.boot.resttestclient}（artifact 拆为 {@code spring-boot-resttestclient}）。
 * 同一个简单名无法同时 import 两个包，故按 profile 选择不同的 source root：
 * <ul>
 * <li>SB3（默认）：本文件</li>
 * <li>SB4：{@code src/main/sb4/java} 下的同名同包类</li>
 * </ul>
 * 两者都直接 {@code extends} 对应版本的真实实现，方法自动继承 —— 因此零转发代码，
 * 调用方（examples 的 E2E 测试）只需统一 import 本类。
 * </p>
 */
public class TestRestTemplate extends org.springframework.boot.test.web.client.TestRestTemplate {

    /** 接收本 shim 的 builder（内部 unwrap 成该版本真实的 {@code RestTemplateBuilder}）。 */
    public TestRestTemplate(RestTemplateBuilder builder) {
        super(builder.unwrap());
    }

    /** 无参（= varargs 空参）：两版本都提供 {@code TestRestTemplate(HttpClientOption...)}。 */
    public TestRestTemplate() {
        super();
    }
}
