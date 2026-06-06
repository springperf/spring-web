package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.match.Matcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.web.mappings.MappingDescriptionProvider;
import org.springframework.context.ApplicationContext;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActuatorMappingDescriptionProviderTest {

    @Mock
    WebContext webContext;

    @Mock
    MappingRegistry mappingRegistry;

    @Mock
    PathMappingContext pathMappingContext;

    @Mock
    ApplicationContext applicationContext;

    @Test
    void getMappingName_returnsPerfMappings() {
        ActuatorMappingDescriptionProvider provider = new ActuatorMappingDescriptionProvider(webContext);
        assertEquals("perfMappings", provider.getMappingName());
    }

    @Test
    void implementsMappingDescriptionProvider() {
        ActuatorMappingDescriptionProvider provider = new ActuatorMappingDescriptionProvider(webContext);
        assertInstanceOf(MappingDescriptionProvider.class, provider);
    }

    @SuppressWarnings("unchecked")
    @Test
    void describeMappings_noRegistry_returnsEmptyMap() {
        when(webContext.getWebComponent(MappingRegistry.class)).thenReturn(null);

        ActuatorMappingDescriptionProvider provider = new ActuatorMappingDescriptionProvider(webContext);
        Object result = provider.describeMappings(applicationContext);

        assertInstanceOf(Map.class, result);
        assertTrue(((Map<String, Object>) result).isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void describeMappings_withEmptyRegistry_returnsDispatcherHandlerList() {
        when(webContext.getWebComponent(MappingRegistry.class)).thenReturn(mappingRegistry);
        when(mappingRegistry.getMappingContextList()).thenReturn(Collections.emptyList());

        ActuatorMappingDescriptionProvider provider = new ActuatorMappingDescriptionProvider(webContext);
        Object result = provider.describeMappings(applicationContext);

        assertInstanceOf(Map.class, result);
        Map<String, Object> wrapper = (Map<String, Object>) result;
        assertTrue(wrapper.containsKey("dispatcherHandler"));
        assertTrue(((List<Object>) wrapper.get("dispatcherHandler")).isEmpty());
    }

    @Test
    void describeMappings_withRoute_describesRoute() {
        when(webContext.getWebComponent(MappingRegistry.class)).thenReturn(mappingRegistry);
        when(mappingRegistry.getMappingContextList()).thenReturn(Collections.singletonList(pathMappingContext));
        when(pathMappingContext.getBean()).thenReturn(new Object());
        when(pathMappingContext.getPathRule()).thenReturn("/test");
        when(pathMappingContext.getMatchers()).thenReturn(new Matcher[0]);

        ActuatorMappingDescriptionProvider provider = new ActuatorMappingDescriptionProvider(webContext);
        Object result = provider.describeMappings(applicationContext);

        assertNotNull(result);
    }
}