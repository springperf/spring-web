package io.springperf.web.context;

public interface LifecycleWebComponent extends WebComponent {

    default void initComponentPhase1() throws Exception {

    }

    default void initComponentPhase2() throws Exception {

    }

    default void initComponentPhase3() throws Exception {

    }

    default void destroyComponent() throws Exception {

    }
}
