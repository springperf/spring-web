package io.springperf.web.core.resource;

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
        for (ResourceHandlerRegistration registration : registrations) {
            for (PathMappingContext mappingContext : registration.buildPathMappingContext()) {
                mappingRegistry.registerMapping(mappingContext);
            }
        }
    }


}
