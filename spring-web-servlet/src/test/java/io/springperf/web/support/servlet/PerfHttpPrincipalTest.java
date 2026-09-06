package io.springperf.web.support.servlet;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class PerfHttpPrincipalTest {

    @Test
    void getName() {
        assertEquals("admin", new PerfHttpPrincipal("admin").getName());
    }

    @Test
    void getName_null_throws() {
        assertThrows(IllegalArgumentException.class, () -> new PerfHttpPrincipal(null));
    }

    @Test
    void hasRole() {
        PerfHttpPrincipal principal = new PerfHttpPrincipal("admin", new HashSet<>(Arrays.asList("admin", "user")));
        assertTrue(principal.hasRole("admin"));
        assertTrue(principal.hasRole("user"));
        assertFalse(principal.hasRole("guest"));
    }

    @Test
    void hasRole_noRoles() {
        PerfHttpPrincipal principal = new PerfHttpPrincipal("user");
        assertFalse(principal.hasRole("admin"));
    }

    @Test
    void getRoles() {
        PerfHttpPrincipal principal = new PerfHttpPrincipal("admin", new HashSet<>(Arrays.asList("admin", "user")));
        assertEquals(2, principal.getRoles().size());
        assertTrue(principal.getRoles().contains("admin"));
    }

    @Test
    void getRoles_immutable() {
        PerfHttpPrincipal principal = new PerfHttpPrincipal("admin", new HashSet<>(Arrays.asList("admin")));
        assertThrows(UnsupportedOperationException.class, () -> principal.getRoles().add("user"));
    }

    @Test
    void equals() {
        PerfHttpPrincipal p1 = new PerfHttpPrincipal("admin");
        PerfHttpPrincipal p2 = new PerfHttpPrincipal("admin");
        assertEquals(p1, p2);
    }

    @Test
    void equals_differentName() {
        PerfHttpPrincipal p1 = new PerfHttpPrincipal("admin");
        PerfHttpPrincipal p2 = new PerfHttpPrincipal("user");
        assertNotEquals(p1, p2);
    }

    @Test
    void hashCode_equals() {
        PerfHttpPrincipal p1 = new PerfHttpPrincipal("admin");
        PerfHttpPrincipal p2 = new PerfHttpPrincipal("admin");
        assertEquals(p1.hashCode(), p2.hashCode());
    }

    @Test
    void toString_returnsName() {
        assertEquals("admin", new PerfHttpPrincipal("admin").toString());
    }
}