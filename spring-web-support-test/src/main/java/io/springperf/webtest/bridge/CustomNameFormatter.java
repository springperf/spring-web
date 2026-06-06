package io.springperf.webtest.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.Formatter;

import java.text.ParseException;
import java.util.Locale;

/**
 * A custom {@link Formatter} registered via
 * {@link org.springframework.web.servlet.config.annotation.WebMvcConfigurer#addFormatters}.
 * <p>
 * On {@link #print}, returns {@code formatted:<value>} to verify the formatter
 * is applied during response rendering.
 */
public class CustomNameFormatter implements Formatter<CustomName> {

    private static final Logger log = LoggerFactory.getLogger(CustomNameFormatter.class);

    @Override
    public CustomName parse(String text, Locale locale) throws ParseException {
        log.info("CustomNameFormatter.parse called with text={}", text);
        return new CustomName(text);
    }

    @Override
    public String print(CustomName object, Locale locale) {
        String result = "formatted:" + object.getValue();
        log.info("CustomNameFormatter.print called with object={}, result={}", object.getValue(), result);
        return result;
    }
}
