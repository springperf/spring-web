package io.springperf.web.context;

/**
 * Extended {@link WebComponent} with a three-phase initialization lifecycle.
 *
 * <p>Components that require ordered initialization should implement this
 * interface instead of plain {@link WebComponent}. The three phases provide
 * clear separation of concerns:</p>
 *
 * <ul>
 *   <li><strong>Phase 1</strong> — bean scanning, metadata collection, data structure construction</li>
 *   <li><strong>Phase 2</strong> — cross-component wiring, index building</li>
 *   <li><strong>Phase 3</strong> — optimization, caching, final preparation for request handling</li>
 * </ul>
 *
 * <p>All phases are executed sequentially in the order above after every
 * component has received its {@link #initWithWebContext(WebContext)} call.</p>
 *
 * @since 1.0.0
 * @see WebComponent
 * @see #initComponentPhase1()
 * @see #initComponentPhase2()
 * @see #initComponentPhase3()
 */
public interface LifecycleWebComponent extends WebComponent {

    /**
     * First initialization phase: scan and collect metadata.
     *
     * <p>Typical work includes scanning Spring beans for annotations,
     * building internal data structures, and collecting configuration.
     * This phase runs before any cross-component wiring occurs.</p>
     *
     * @throws Exception if initialization fails
     */
    default void initComponentPhase1() throws Exception {

    }

    /**
     * Second initialization phase: cross-component wiring.
     *
     * <p>Components may resolve references to other components that were
     * populated during Phase 1, build indices, and establish connections
     * between data structures from different components.</p>
     *
     * @throws Exception if initialization fails
     */
    default void initComponentPhase2() throws Exception {

    }

    /**
     * Third initialization phase: optimization and final preparation.
     *
     * <p>Components perform optimizations (e.g., route tree compaction,
     * pattern pre-compilation), cache pre-computed results, and perform
     * any other final setup before the first request arrives.</p>
     *
     * @throws Exception if initialization fails
     */
    default void initComponentPhase3() throws Exception {

    }

    /**
     * Clean up resources held by this component.
     *
     * <p>Called during application shutdown. Implementations should release
     * thread pools, close file handles, and perform other cleanup. This
     * method is idempotent — calling it multiple times must be safe.</p>
     *
     * @throws Exception if cleanup fails
     */
    default void destroyComponent() throws Exception {

    }
}
