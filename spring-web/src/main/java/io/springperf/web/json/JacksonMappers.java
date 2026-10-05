package io.springperf.web.json;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 框架内 Jackson 3（{@code tools.jackson}）{@code ObjectMapper} 的统一构造入口。
 * <p>
 * <b>为什么需要它</b>：Jackson 3 有一处与 Jackson 2 相反的默认值——{@code FAIL_ON_NULL_FOR_PRIMITIVES}
 * 从 {@code false} 改为 {@code true}。即 JSON 里的 {@code null} 赋给 {@code int}/{@code boolean}
 * 等基本类型字段时，Jackson 2（及 Spring Boot 3）静默给 {@code 0}/{@code false}，
 * Jackson 3 默认抛 {@code MismatchedInputException}。
 * <p>
 * 框架在多个位置自行构造 mapper（{@link JacksonConverter}、{@code JacksonHttpBodyConverter}、
 * {@code AsyncSupportRegistry}）。若各自 {@code new}，一旦要调整行为就会漏改；故集中到这里，
 * 并显式关掉该 feature 以保持与 Jackson 2 / Spring Boot 3 一致的向后兼容语义——
 * 升级框架不应让既有业务代码因为 JSON 里多了一个 {@code null} 就开始报错。
 * <p>
 * 注意：当容器已提供 {@code ObjectMapper} bean（Spring Boot 的自动配置）时，框架优先使用它，
 * 本工厂只在需要兜底时生效。那时业务方若想要 Jackson 3 的严格语义，可在自己的
 * {@code JacksonJsonMapperBuilderCustomizer} 里开启。
 */
public final class JacksonMappers {

    private JacksonMappers() {
    }

    /**
     * 构造框架默认使用的 {@code ObjectMapper}（向后兼容配置）。
     */
    public static JsonMapper defaultMapper() {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
    }
}
