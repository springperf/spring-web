package io.springperf.web.http;

public class ConnectionContext {

    private boolean lastWritable = true;

    private Runnable onWritable;

    public Runnable getOnWritable() {
        return onWritable;
    }

    public void setOnWritable(Runnable cb) {
        this.onWritable = cb;
    }

    boolean lastWritable() {
        return lastWritable;
    }

    void updateWritable(boolean writable) {
        this.lastWritable = writable;
    }
}
