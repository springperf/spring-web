package io.springperf.web.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteRespEventListenerTest {

    @Test
    void defaultMethods_doNotThrow() {
        WriteRespEventListener listener = new WriteRespEventListener() {
            @Override
            public void completeSuccessCallback() {}
            @Override
            public void completeErrorCallback(Throwable throwable) {}
        };

        // Default methods should not throw
        listener.writeStreamSuccessCallback();
        listener.writeStreamErrorCallback(new RuntimeException("test"));
    }

    @Test
    void completeSuccessCallback_calledOnSuccess() {
        final boolean[] called = {false};
        WriteRespEventListener listener = new WriteRespEventListener() {
            @Override
            public void completeSuccessCallback() { called[0] = true; }
            @Override
            public void completeErrorCallback(Throwable throwable) {}
        };
        listener.completeSuccessCallback();
        assertTrue(called[0]);
    }

    @Test
    void completeErrorCallback_calledOnError() {
        final boolean[] called = {false};
        WriteRespEventListener listener = new WriteRespEventListener() {
            @Override
            public void completeSuccessCallback() {}
            @Override
            public void completeErrorCallback(Throwable throwable) { called[0] = true; }
        };
        listener.completeErrorCallback(new RuntimeException("error"));
        assertTrue(called[0]);
    }
}