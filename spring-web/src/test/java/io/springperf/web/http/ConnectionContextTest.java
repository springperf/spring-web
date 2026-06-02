package io.springperf.web.http;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionContextTest {

    @Test
    void lastWritable_default_returnsTrue() {
        ConnectionContext ctx = new ConnectionContext();
        assertTrue(ctx.lastWritable());
    }

    @Test
    void updateWritable_changesLastWritable() {
        ConnectionContext ctx = new ConnectionContext();
        ctx.updateWritable(false);
        assertFalse(ctx.lastWritable());
        ctx.updateWritable(true);
        assertTrue(ctx.lastWritable());
    }

    @Test
    void onWritable_default_returnsNull() {
        ConnectionContext ctx = new ConnectionContext();
        assertNull(ctx.getOnWritable());
    }

    @Test
    void setOnWritable_setsCallback() {
        ConnectionContext ctx = new ConnectionContext();
        Runnable cb = () -> {};
        ctx.setOnWritable(cb);
        assertSame(cb, ctx.getOnWritable());
    }

    @Test
    void setOnWritable_overwritesPrevious() {
        ConnectionContext ctx = new ConnectionContext();
        ctx.setOnWritable(() -> {});
        Runnable cb2 = () -> {};
        ctx.setOnWritable(cb2);
        assertSame(cb2, ctx.getOnWritable());
    }

    @Test
    void updateWritable_and_onWritable_interact() {
        ConnectionContext ctx = new ConnectionContext();
        AtomicBoolean fired = new AtomicBoolean(false);
        ctx.setOnWritable(() -> fired.set(true));

        ctx.updateWritable(false);
        assertFalse(ctx.lastWritable());

        ctx.updateWritable(true);
        assertTrue(ctx.lastWritable());
        // BackpressureHandler checks lastWritable before calling onWritable,
        // but ConnectionContext itself just stores state
        assertTrue(ctx.lastWritable());
    }
}