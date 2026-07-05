package io.springperf.web.core.resource;

import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ResourceHandlerRegistrationTest {

    @Test
    void constructor_requiresPathPatterns() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ResourceHandlerRegistration());
        assertTrue(ex.getMessage().contains("path pattern"));
    }

    @Test
    void constructor_singlePathPattern() {
        ResourceHandlerRegistration registration = new ResourceHandlerRegistration("/static/**");

        assertArrayEquals(new String[]{"/static/**"}, registration.getPathPatterns());
    }

    @Test
    void constructor_multiplePathPatterns() {
        ResourceHandlerRegistration registration = new ResourceHandlerRegistration("/images/**", "/css/**", "/js/**");

        assertArrayEquals(new String[]{"/images/**", "/css/**", "/js/**"}, registration.getPathPatterns());
    }

    @Test
    void addResourceLocations_addsLocation() {
        ResourceHandlerRegistration registration = new ResourceHandlerRegistration("/static/**");

        registration.addResourceLocations("classpath:/static/", "file:/opt/static/");

        assertEquals(2, registration.getLocationValues().size());
        assertTrue(registration.getLocationValues().contains("classpath:/static"));
        assertTrue(registration.getLocationValues().contains("file:/opt/static"));
    }

    @Test
    void addResourceLocations_returnsSelf() {
        ResourceHandlerRegistration registration = new ResourceHandlerRegistration("/static/**");

        ResourceHandlerRegistration result = registration.addResourceLocations("classpath:/static/");

        assertSame(registration, result);
    }

    @Test
    void addResourceLocations_multipleCalls_appends() {
        ResourceHandlerRegistration registration = new ResourceHandlerRegistration("/static/**");

        registration.addResourceLocations("classpath:/static/");
        registration.addResourceLocations("classpath:/public/");

        assertEquals(2, registration.getLocationValues().size());
    }

    @Test
    void setCachePeriod_storesValue() {
        ResourceHandlerRegistration registration = new ResourceHandlerRegistration("/static/**");

        registration.setCachePeriod(3600);

        assertEquals(Integer.valueOf(3600), registration.getCachePeriod());
    }

    @Test
    void setCachePeriod_returnsSelf() {
        ResourceHandlerRegistration registration = new ResourceHandlerRegistration("/static/**");

        ResourceHandlerRegistration result = registration.setCachePeriod(3600);

        assertSame(registration, result);
    }

    @Test
    void setCachePeriod_nullValue() {
        ResourceHandlerRegistration registration = new ResourceHandlerRegistration("/static/**");
        registration.setCachePeriod(3600);

        registration.setCachePeriod(null);

        assertNull(registration.getCachePeriod());
    }

    @Test
    void setCacheControl_storesValue() {
        ResourceHandlerRegistration registration = new ResourceHandlerRegistration("/static/**");
        CacheControl cacheControl = CacheControl.maxAge(1, TimeUnit.HOURS);

        registration.setCacheControl(cacheControl);

        assertSame(cacheControl, registration.getCacheControl());
    }

    @Test
    void setCacheControl_returnsSelf() {
        ResourceHandlerRegistration registration = new ResourceHandlerRegistration("/static/**");

        ResourceHandlerRegistration result = registration.setCacheControl(CacheControl.noCache());

        assertSame(registration, result);
    }

    @Test
    void getCacheControl_defaultIsNull() {
        ResourceHandlerRegistration registration = new ResourceHandlerRegistration("/static/**");

        assertNull(registration.getCacheControl());
    }

    @Test
    void getCachePeriod_defaultIsNull() {
        ResourceHandlerRegistration registration = new ResourceHandlerRegistration("/static/**");

        assertNull(registration.getCachePeriod());
    }

    @Test
    void getLocationValues_emptyByDefault() {
        ResourceHandlerRegistration registration = new ResourceHandlerRegistration("/static/**");

        assertTrue(registration.getLocationValues().isEmpty());
    }

    @Test
    void buildPathMappingContext_returnsMappings() {
        ResourceHandlerRegistration registration = new ResourceHandlerRegistration("/static/**");
        registration.addResourceLocations("classpath:/static/");

        assertFalse(registration.buildPathMappingContext().isEmpty());
    }

    @Test
    void buildPathMappingContext_multiplePatterns() {
        ResourceHandlerRegistration registration = new ResourceHandlerRegistration("/images/**", "/css/**");
        registration.addResourceLocations("classpath:/static/");

        assertEquals(2, registration.buildPathMappingContext().size());
    }

    @Test
    void getResourceRequestHandler_returnsHandler() {
        ResourceHandlerRegistration registration = new ResourceHandlerRegistration("/static/**");

        assertNotNull(registration.getResourceRequestHandler());
    }
}