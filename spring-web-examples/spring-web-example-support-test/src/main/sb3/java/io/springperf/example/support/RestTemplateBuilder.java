package io.springperf.example.support;

import java.time.Duration;

/**
 * 跨版本 {@code RestTemplateBuilder} 薄封装（Spring Boot 3 版）。
 * <p>
 * Boot 3 位于 {@code org.springframework.boot.web.client}，Boot 4 移到
 * {@code org.springframework.boot.restclient}（artifact：{@code spring-boot-restclient}）。
 * 按 profile 选择 source root，两份同名同包。
 * </p>
 * <p>
 * <b>为什么用组合而非继承</b>：父类是<b>不可变</b>的 —— {@code rootUri/connectTimeout/...} 都
 * {@code return new RestTemplateBuilder(...)}。若靠继承覆写，{@code super.rootUri(...)} 返回的是
 * <b>父类型新实例</b>，链式调用的后续 shim 方法就丢了（实测会报 "URI with undefined scheme"）。
 * 故此处持有一个父实例，每个方法更新它并返回 {@code this}。
 * </p>
 * <p>
 * 超时方法名两版本不同：SB3 是 {@code setConnectTimeout/setReadTimeout}，
 * SB4 是 {@code connectTimeout/readTimeout}；本 shim 统一为
 * {@link #connectTimeout}/{@link #readTimeout}。
 * </p>
 */
public class RestTemplateBuilder {

    private org.springframework.boot.web.client.RestTemplateBuilder delegate =
            new org.springframework.boot.web.client.RestTemplateBuilder();

    /** 设置根 URI。 */
    public RestTemplateBuilder rootUri(String rootUri) {
        this.delegate = this.delegate.rootUri(rootUri);
        return this;
    }

    /** 跨版本统一名：设置连接超时（SB3 转发到 setConnectTimeout）。 */
    public RestTemplateBuilder connectTimeout(Duration timeout) {
        this.delegate = this.delegate.setConnectTimeout(timeout);
        return this;
    }

    /** 跨版本统一名：设置读取超时（SB3 转发到 setReadTimeout）。 */
    public RestTemplateBuilder readTimeout(Duration timeout) {
        this.delegate = this.delegate.setReadTimeout(timeout);
        return this;
    }

    /** 供 {@code TestRestTemplate} 构造器使用的底层 builder。 */
    public org.springframework.boot.web.client.RestTemplateBuilder unwrap() {
        return this.delegate;
    }
}
