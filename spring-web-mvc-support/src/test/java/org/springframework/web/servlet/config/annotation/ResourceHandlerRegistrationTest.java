package org.springframework.web.servlet.config.annotation;

import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ResourceHandlerRegistrationTest {

    @Test
    void constructor_setsPathPatterns() {
        ResourceHandlerRegistration reg = new ResourceHandlerRegistration("/static/**");
        assertArrayEquals(new String[]{"/static/**"}, reg.getPathPatterns());
    }

    @Test
    void constructor_multiplePatterns() {
        ResourceHandlerRegistration reg = new ResourceHandlerRegistration("/img/**", "/css/**");
        assertArrayEquals(new String[]{"/img/**", "/css/**"}, reg.getPathPatterns());
    }

    @Test
    void addResourceLocations_addsLocations() {
        ResourceHandlerRegistration reg = new ResourceHandlerRegistration("/static/**")
                .addResourceLocations("classpath:/static/", "file:/var/www/");

        assertEquals(2, reg.getLocationValues().size());
        assertTrue(reg.getLocationValues().contains("classpath:/static/"));
    }

    @Test
    void setCachePeriod_storesPeriod() {
        ResourceHandlerRegistration reg = new ResourceHandlerRegistration("/static/**")
                .setCachePeriod(3600);

        assertEquals(Integer.valueOf(3600), reg.getCachePeriod());
    }

    @Test
    void setCacheControl_storesControl() {
        CacheControl cc = CacheControl.maxAge(1, TimeUnit.HOURS);
        ResourceHandlerRegistration reg = new ResourceHandlerRegistration("/static/**")
                .setCacheControl(cc);

        assertSame(cc, reg.getCacheControl());
    }

    @Test
    void defaultValues_areNull() {
        ResourceHandlerRegistration reg = new ResourceHandlerRegistration("/static/**");

        assertTrue(reg.getLocationValues().isEmpty());
        assertNull(reg.getCachePeriod());
        assertNull(reg.getCacheControl());
    }

    @Test
    void chainedCalls_returnsSelf() {
        ResourceHandlerRegistration reg = new ResourceHandlerRegistration("/static/**")
                .addResourceLocations("classpath:/static/")
                .setCachePeriod(600)
                .setCacheControl(CacheControl.noCache());

        assertNotNull(reg);
        assertEquals(1, reg.getLocationValues().size());
        assertEquals(Integer.valueOf(600), reg.getCachePeriod());
    }
}