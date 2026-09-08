package io.springperf.web.support.servlet;

import java.security.Principal;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class PerfHttpPrincipal implements Principal {

    private final String name;
    private final Set<String> roles;

    public PerfHttpPrincipal(String name) {
        this(name, Collections.emptySet());
    }

    public PerfHttpPrincipal(String name, Set<String> roles) {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }
        this.name = name;
        this.roles = roles != null
                ? Collections.unmodifiableSet(new HashSet<>(roles))
                : Collections.emptySet();
    }

    @Override
    public String getName() {
        return name;
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public Set<String> getRoles() {
        return roles;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PerfHttpPrincipal)) return false;
        return name.equals(((PerfHttpPrincipal) o).name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}