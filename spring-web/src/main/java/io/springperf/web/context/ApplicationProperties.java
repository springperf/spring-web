package io.springperf.web.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertyResolver;

/**
 * Generic property accessor. Provides only typed getter methods;
 * property keys are defined in {@link PropertiesConstant}.
 */
@Slf4j
public class ApplicationProperties implements EnvironmentAware {

    private PropertyResolver properties;

    public String get(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return Integer.parseInt(properties.getProperty(key, String.valueOf(defaultValue)));
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(properties.getProperty(key, String.valueOf(defaultValue)));
    }

    public long getLong(String key, long defaultValue) {
        return Long.parseLong(properties.getProperty(key, String.valueOf(defaultValue)));
    }

    public double getDouble(String key, double defaultValue) {
        return Double.parseDouble(properties.getProperty(key, String.valueOf(defaultValue)));
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.properties = environment;
    }
}
