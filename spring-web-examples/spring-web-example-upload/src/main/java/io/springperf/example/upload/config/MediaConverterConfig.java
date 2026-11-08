package io.springperf.example.upload.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.ResourceHttpMessageConverter;

/**
 * 补上 {@code Resource} 的写出能力。
 * <p>
 * 框架默认只注册 Jackson 转换器；容器中的任意 {@code HttpMessageConverter} Bean 都会被自动适配为 {@code HttpBodyConverter}。因此下载接口只要返回
 * {@code ResponseEntity<Resource>}，就需要显式提供这个 Bean—— 否则 3.5.8 起会按「内容协商失败」返回 500（此前是静默的 200 + 空 body）。
 * </p>
 */
@Configuration
public class MediaConverterConfig {

    @Bean
    public ResourceHttpMessageConverter resourceHttpMessageConverter() {
        return new ResourceHttpMessageConverter();
    }
}
