package io.springperf.web.context;

import lombok.extern.slf4j.Slf4j;

/**
 * 配置动态刷新的协调器：统一使框架内「以配置值为内容」的缓存失效并重解析。
 * <p>
 * <b>背景</b>：框架为性能把配置读取结果缓存为快照——{@link ApplicationProperties} 的 map 缓存（copy-on-write）与热路径字段（构造期急切解析）。配置中心 （如 Spring
 * Cloud Config / Nacos）在运行期更新配置后，这些快照不会自动失效， 导致框架继续使用旧值。
 * </p>
 * <p>
 * <b>职责边界</b>：本类只触发 {@link ApplicationProperties#clearCache()}—— 它同时清空 map 缓存并重解析全部热路径字段（{@code server.http.timeout}、
 * {@code server.max-parameter-count}、{@code server.http.max-in-memory-size} 等）， 失败字段保留旧值。
 * </p>
 * <p>
 * <b>不生效的部分（务必先读）</b>：凡是把配置在<b>启动期解析成对象并持有</b>的组件，都不在本刷新范围内， 它们需要重建组件才能生效。已知清单：
 * </p>
 * <ul>
 * <li>Netty 服务器与管线：{@code server.port}、{@code server.netty.*}、{@code CompressionConfig}、
 * {@code KeepAliveConfig}、{@code MultipartConfig}、{@code ResponseLimitConfig}（在 {@code NettyHttpServer} 构造时固化）；</li>
 * <li>业务线程池：{@code pool.*} / {@code spring.threads.virtual.enabled}（池结构建成后不再改变）；</li>
 * <li>访问日志：{@code server.accesslog.*}（{@code AccessLogWriter.fromProperties} 在 Bean 创建时固化）；</li>
 * <li>区域设置：{@code spring.web.locale.*}（{@code LocaleConfig.fromProperties} 同样在装配时固化）；</li>
 * <li>模板引擎：{@code spring.{thymeleaf,freemarker,beetl}.*}（{@code TemplateEngine} 为单例）。</li>
 * </ul>
 * <p>
 * 修改上述配置后请重启进程；不要因为调用过 {@link #refresh()} 就认为它们已生效。
 * </p>
 * <p>
 * <b>触发方式</b>：由 starter 侧的 {@code SpringWebCloudRefreshAutoConfiguration} 监听 Spring Cloud
 * {@code EnvironmentChangeEvent} 后调用（见该类）。 业务代码也可主动调用以配合自定义刷新通道。
 * </p>
 *
 * @since 3.5.7
 *
 * @see ApplicationProperties#clearCache()
 */
@Slf4j
public class PropertyRefreshHandler {

    private final WebContext webContext;

    public PropertyRefreshHandler(WebContext webContext) {
        this.webContext = webContext;
    }

    /**
     * 清空全部框架级配置缓存并重解析热路径字段，使后续读取回落到 {@code Environment} 最新值。
     * <p>
     * 幂等且线程安全：map 缓存以 volatile 换新表发布，热路径字段为 volatile 赋值， 读路径无锁不受影响。
     * </p>
     */
    public void refresh() {
        webContext.getProps().clearCache();
        log.info("Property caches cleared and hot-path fields re-resolved; "
                + "subsequent reads fall back to Environment");
    }
}
