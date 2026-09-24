package io.springperf.web.core.resource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;

/**
 * 静态资源全局配置（对齐 Spring Boot {@code spring.web.resources.*}），启动期预解析一次。
 * <ul>
 * <li>{@code add-mappings}：是否启用框架内置默认资源映射（**默认 false**，显式开启；与用户注册共存）</li>
 * <li>{@code static-locations}：默认资源位置（逗号分隔；默认对齐 Boot 的 4 个 classpath 位置）</li>
 * <li>{@code cache.period} / {@code cache.cachecontrol.max-age}：默认映射的 Cache-Control 时长（秒）</li>
 * </ul>
 */
public final class WebResourcesConfig {

    /** 默认配置：不启用自动映射（保持历史行为）、Boot 默认 4 位置、不设置缓存。 */
    public static final WebResourcesConfig DEFAULT = new WebResourcesConfig(
            PropertiesConstant.WEB_RESOURCES_ADD_MAPPINGS_DEFAULT,
            parseLocations(PropertiesConstant.WEB_RESOURCES_STATIC_LOCATIONS_DEFAULT), null);

    private final boolean addMappings;
    private final List<String> staticLocations;
    /** 缓存秒数；null 表示不设置 Cache-Control。 */
    private final Long cachePeriodSeconds;

    public WebResourcesConfig(boolean addMappings, List<String> staticLocations, Long cachePeriodSeconds) {
        this.addMappings = addMappings;
        this.staticLocations = staticLocations != null ? staticLocations : Collections.emptyList();
        this.cachePeriodSeconds = cachePeriodSeconds;
    }

    public static WebResourcesConfig fromProperties(ApplicationProperties props) {
        boolean addMappings = props.getBoolean(PropertiesConstant.WEB_RESOURCES_ADD_MAPPINGS,
                PropertiesConstant.WEB_RESOURCES_ADD_MAPPINGS_DEFAULT);
        List<String> locations = parseLocations(props.get(PropertiesConstant.WEB_RESOURCES_STATIC_LOCATIONS,
                PropertiesConstant.WEB_RESOURCES_STATIC_LOCATIONS_DEFAULT));
        // 优先 max-age，其次 cache.period；两者均为 Duration/秒数（裸数字按秒）
        Long cacheSeconds = parseSeconds(props, PropertiesConstant.WEB_RESOURCES_CACHE_CONTROL_MAX_AGE);
        if (cacheSeconds == null) {
            cacheSeconds = parseSeconds(props, PropertiesConstant.WEB_RESOURCES_CACHE_PERIOD);
        }
        return new WebResourcesConfig(addMappings, locations, cacheSeconds);
    }

    /** Duration 或裸秒数解析；未配置/非法返回 null（不设置缓存）。 */
    private static Long parseSeconds(ApplicationProperties props, String key) {
        String raw = props.get(key, null);
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            // 复用 getDurationSeconds 的语义：裸数字=秒、支持 30s/1m/1h/1d
            return props.getDurationSeconds(key, 0L);
        } catch (Exception e) {
            return null;
        }
    }

    /** 逗号分隔位置解析：去空白、去尾部斜杠（与 {@code addResourceLocations} 归一化一致）。 */
    static List<String> parseLocations(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return result;
        }
        for (String item : raw.split(",")) {
            String loc = item.trim();
            if (loc.isEmpty()) {
                continue;
            }
            while (loc.endsWith("/") && loc.length() > 1) {
                loc = loc.substring(0, loc.length() - 1);
            }
            result.add(loc);
        }
        return result;
    }

    public boolean isAddMappings() {
        return addMappings;
    }

    public List<String> getStaticLocations() {
        // 只读视图；字段为 null 时保持原有返回 null 的行为
        return staticLocations == null ? null : java.util.Collections.unmodifiableList(staticLocations);
    }

    /** 默认缓存秒数；null 表示不设置 Cache-Control。 */
    public Long getCachePeriodSeconds() {
        return cachePeriodSeconds;
    }
}
