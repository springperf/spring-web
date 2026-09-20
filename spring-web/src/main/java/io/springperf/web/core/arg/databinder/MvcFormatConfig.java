package io.springperf.web.core.arg.databinder;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import org.springframework.format.FormatterRegistry;
import org.springframework.format.datetime.standard.DateTimeFormatterRegistrar;

import java.time.format.DateTimeFormatter;

/**
 * MVC 日期时间格式配置（对齐 {@code spring.mvc.format.date} / {@code .time} / {@code .datetime}）。
 * 启动期预解析一次，并为无 {@code @DateTimeFormat} 注解的字段注册全局默认格式
 * （对齐 Boot 的 {@code DateTimeFormatterRegistrar} 用法）。
 */
public class MvcFormatConfig {

    /** 默认：ISO 风格（yyyy-MM-dd / HH:mm:ss / yyyy-MM-dd'T'HH:mm:ss）。 */
    public static final MvcFormatConfig DEFAULT = new MvcFormatConfig(
            PropertiesConstant.MVC_FORMAT_DATE_DEFAULT,
            PropertiesConstant.MVC_FORMAT_TIME_DEFAULT,
            PropertiesConstant.MVC_FORMAT_DATETIME_DEFAULT);

    private final String datePattern;
    private final String timePattern;
    private final String dateTimePattern;

    public MvcFormatConfig(String datePattern, String timePattern, String dateTimePattern) {
        this.datePattern = datePattern;
        this.timePattern = timePattern;
        this.dateTimePattern = dateTimePattern;
    }

    public static MvcFormatConfig fromProperties(ApplicationProperties props) {
        return new MvcFormatConfig(
                props.get(PropertiesConstant.MVC_FORMAT_DATE, PropertiesConstant.MVC_FORMAT_DATE_DEFAULT),
                props.get(PropertiesConstant.MVC_FORMAT_TIME, PropertiesConstant.MVC_FORMAT_TIME_DEFAULT),
                props.get(PropertiesConstant.MVC_FORMAT_DATETIME, PropertiesConstant.MVC_FORMAT_DATETIME_DEFAULT));
    }

    public String getDatePattern() {
        return datePattern;
    }

    public String getTimePattern() {
        return timePattern;
    }

    public String getDateTimePattern() {
        return dateTimePattern;
    }

    /**
     * 将全局日期/时间/日期时间格式注册到给定 {@link FormatterRegistry}。
     * 仅当对应 pattern 非空时注册，未配置的维度沿用 Spring 默认（DateFormat.SHORT）行为。
     */
    public void applyTo(FormatterRegistry registry) {
        DateTimeFormatterRegistrar registrar = new DateTimeFormatterRegistrar();
        if (notBlank(datePattern)) {
            registrar.setDateFormatter(DateTimeFormatter.ofPattern(datePattern));
        }
        if (notBlank(timePattern)) {
            registrar.setTimeFormatter(DateTimeFormatter.ofPattern(timePattern));
        }
        if (notBlank(dateTimePattern)) {
            registrar.setDateTimeFormatter(DateTimeFormatter.ofPattern(dateTimePattern));
        }
        registrar.registerFormatters(registry);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
