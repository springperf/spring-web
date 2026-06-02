package io.springperf.web.core.mapping.match;

import org.springframework.lang.Nullable;

import java.util.Objects;

public class NameValueExpressionSupport {

    protected final String name;

    @Nullable
    protected final String value;

    protected final boolean negated;

    public NameValueExpressionSupport(String name, @Nullable String value, boolean negated) {
        this.name = name;
        this.value = value;
        this.negated = negated;
    }

    public String getName() {
        return name;
    }

    @Nullable
    public String getValue() {
        return value;
    }

    public boolean isNegated() {
        return negated;
    }

    public static NameValueExpressionSupport build(String expression) {
        boolean isNegated;
        String name;
        String value;
        int separator = expression.indexOf('=');
        if (separator == -1) {
            isNegated = expression.startsWith("!");
            name = (isNegated ? expression.substring(1) : expression);
            value = null;
        } else {
            isNegated = (separator > 0) && (expression.charAt(separator - 1) == '!');
            name = (isNegated ? expression.substring(0, separator - 1) : expression.substring(0, separator));
            value = expression.substring(separator + 1);
        }
        return new NameValueExpressionSupport(name, value, isNegated);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NameValueExpressionSupport that = (NameValueExpressionSupport) o;
        return negated == that.negated && Objects.equals(name, that.name) && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, value, negated);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        if (this.value != null) {
            builder.append(this.name);
            if (this.negated) {
                builder.append('!');
            }
            builder.append('=');
            builder.append(this.value);
        } else {
            if (this.negated) {
                builder.append('!');
            }
            builder.append(this.name);
        }
        return builder.toString();
    }
}
