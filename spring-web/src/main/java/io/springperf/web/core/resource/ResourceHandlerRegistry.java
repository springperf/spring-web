package io.springperf.web.core.resource;

import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebComponentContainer;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;

import java.util.ArrayList;
import java.util.List;

public class ResourceHandlerRegistry extends WebComponentContainer {

    private final List<ResourceHandlerRegistration> registrations = new ArrayList<>();
    private MappingRegistry mappingRegistry;

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        this.mappingRegistry = webContext.getWebComponentWithDefault(MappingRegistry.class, new MappingRegistry());
        registerWebComponent(ResourceHandlerRegistration.class);
    }

    @Override
    public void initComponentPhase2() throws Exception {
        super.initComponentPhase2();
        initRealComponentList(registrations, ResourceHandlerRegistration.class);
        // 静态资源全局配置（spring.web.resources.*）：启动期预解析一次
        WebResourcesConfig resourcesConfig = WebResourcesConfig.fromProperties(webContext.getProps());
        // add-mappings=true：始终自动注册默认 /** → static-locations 映射（对齐 Boot——
        // 用户注册的自定义 handler 与默认映射共存，特异性匹配下用户 pattern 优先命中）。
        // 仅对自动注册应用 spring.web.resources.cache.* 全局缓存默认，不干扰用户显式注册。
        if (resourcesConfig.isAddMappings() && !resourcesConfig.getStaticLocations().isEmpty()) {
            ResourceHandlerRegistration defaultRegistration = new ResourceHandlerRegistration("/**");
            defaultRegistration.addResourceLocations(
                    resourcesConfig.getStaticLocations().toArray(new String[0]));
            Long cacheSeconds = resourcesConfig.getCachePeriodSeconds();
            if (cacheSeconds != null) {
                defaultRegistration.setCachePeriod(Math.toIntExact(cacheSeconds));
            }
            registrations.add(defaultRegistration);
        }
        // 全局静态资源前缀（spring.mvc.static-path-pattern，默认 /** 即无前缀）
        String globalPrefix = webContext.getProps().get(
                PropertiesConstant.MVC_STATIC_PATH_PATTERN, PropertiesConstant.MVC_STATIC_PATH_PATTERN_DEFAULT);
        for (ResourceHandlerRegistration registration : registrations) {
            for (PathMappingContext mappingContext : registration.buildPathMappingContext(globalPrefix)) {
                mappingRegistry.registerMapping(mappingContext);
            }
        }
    }


}
