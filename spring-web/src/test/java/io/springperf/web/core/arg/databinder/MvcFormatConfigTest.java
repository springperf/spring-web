package io.springperf.web.core.arg.databinder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;
import org.springframework.format.support.DefaultFormattingConversionService;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;

class MvcFormatConfigTest {

    @Test
    void defaults_isoStyle() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get(PropertiesConstant.MVC_FORMAT_DATE, PropertiesConstant.MVC_FORMAT_DATE_DEFAULT))
                .thenReturn(PropertiesConstant.MVC_FORMAT_DATE_DEFAULT);
        when(props.get(PropertiesConstant.MVC_FORMAT_TIME, PropertiesConstant.MVC_FORMAT_TIME_DEFAULT))
                .thenReturn(PropertiesConstant.MVC_FORMAT_TIME_DEFAULT);
        when(props.get(PropertiesConstant.MVC_FORMAT_DATETIME, PropertiesConstant.MVC_FORMAT_DATETIME_DEFAULT))
                .thenReturn(PropertiesConstant.MVC_FORMAT_DATETIME_DEFAULT);

        MvcFormatConfig cfg = MvcFormatConfig.fromProperties(props);
        assertEquals("yyyy-MM-dd", cfg.getDatePattern());
        assertEquals("HH:mm:ss", cfg.getTimePattern());
        assertEquals("yyyy-MM-dd'T'HH:mm:ss", cfg.getDateTimePattern());
    }

    @Test
    void applyTo_registersDateFormatter() {
        MvcFormatConfig cfg = new MvcFormatConfig("yyyy/MM/dd", "HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss");
        DefaultFormattingConversionService service = new DefaultFormattingConversionService();
        cfg.applyTo(service);

        // 转换为 LocalDate：应使用自定义 yyyy/MM/dd
        LocalDate date = service.convert("2026/09/14", LocalDate.class);
        assertNotNull(date);
        assertEquals(LocalDate.of(2026, 9, 14), date);
        assertEquals("2026/09/14", service.convert(LocalDate.of(2026, 9, 14), String.class));
    }

    @Test
    void applyTo_registersTimeFormatter() {
        MvcFormatConfig cfg = new MvcFormatConfig("yyyy-MM-dd", "HH-mm-ss", "yyyy-MM-dd'T'HH:mm:ss");
        DefaultFormattingConversionService service = new DefaultFormattingConversionService();
        cfg.applyTo(service);

        LocalTime time = service.convert("13-45-30", LocalTime.class);
        assertNotNull(time);
        assertEquals(LocalTime.of(13, 45, 30), time);
    }

    @Test
    void applyTo_registersDateTimeFormatter() {
        MvcFormatConfig cfg = new MvcFormatConfig("yyyy-MM-dd", "HH:mm:ss", "yyyy/MM/dd HH:mm:ss");
        DefaultFormattingConversionService service = new DefaultFormattingConversionService();
        cfg.applyTo(service);

        LocalDateTime dt = service.convert("2026/09/14 08:30:00", LocalDateTime.class);
        assertNotNull(dt);
        assertEquals(LocalDateTime.of(2026, 9, 14, 8, 30, 0), dt);
    }
}
