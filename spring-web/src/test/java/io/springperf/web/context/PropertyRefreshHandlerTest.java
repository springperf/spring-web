package io.springperf.web.context;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import io.springperf.web.core.DispatcherHandler;

/**
 * {@link PropertyRefreshHandler}：验证刷新会清空 ApplicationProperties 的 map 缓存 并重解析热路径字段快照（timeout / max-parameter-count /
 * max-in-memory-size）。
 */
class PropertyRefreshHandlerTest {

    private ApplicationProperties props;
    private WebContext webContext;
    private Environment env;

    @BeforeEach
    void setUp() {
        env = mock(Environment.class);
        // WebContext 构造会读取 context-path：2-arg 未 stub 时返回 null 会导致 NPE
        lenient().when(env.getProperty(anyString())).thenReturn(null);
        lenient().when(env.getProperty(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1, String.class));
        props = new ApplicationProperties();
        props.setEnvironment(env);
        webContext = new WebContext(mock(DispatcherHandler.class), props);
    }

    @Test
    void refresh_clearsApplicationPropertiesCache() {
        when(env.getProperty(PropertiesConstant.POOL_CORE_POOL_SIZE)).thenReturn("7");
        assertEquals(7, props.getInt(PropertiesConstant.POOL_CORE_POOL_SIZE));

        // 改变 Environment 后未刷新仍为旧值
        when(env.getProperty(PropertiesConstant.POOL_CORE_POOL_SIZE)).thenReturn("8");
        assertEquals(7, props.getInt(PropertiesConstant.POOL_CORE_POOL_SIZE));

        new PropertyRefreshHandler(webContext).refresh();

        assertEquals(8, props.getInt(PropertiesConstant.POOL_CORE_POOL_SIZE), "刷新后应读取 Environment 最新值");
    }

    @Test
    void refresh_reResolvesHotFieldSnapshots() {
        // 构造期已解析（Environment 无配置 → PropertiesConstant 默认值）
        assertEquals(PropertiesConstant.HTTP_MAX_IN_MEMORY_SIZE_DEFAULT, props.getMaxInMemorySize());

        when(env.getProperty(PropertiesConstant.HTTP_MAX_IN_MEMORY_SIZE)).thenReturn("1024");
        when(env.getProperty(PropertiesConstant.SERVER_MAX_PARAMETER_COUNT)).thenReturn("50");

        new PropertyRefreshHandler(webContext).refresh();

        assertEquals(1024, props.getMaxInMemorySize(),
                "刷新后 max-in-memory-size 热字段应重解析（原静态缓存 + clearStaticCache 手工接线的替代）");
        assertEquals(50, props.getMaxParameterCount(), "刷新后 max-parameter-count 热字段应重解析");
    }

    @Test
    void refresh_isIdempotent() {
        when(env.getProperty(anyString())).thenReturn(null);
        PropertyRefreshHandler handler = new PropertyRefreshHandler(webContext);

        assertDoesNotThrow(() -> {
            handler.refresh();
            handler.refresh();
        });
    }

    @Test
    void webContext_refreshProperties_delegatesToHandler() {
        when(env.getProperty(PropertiesConstant.SERVER_PORT)).thenReturn("9999");
        assertEquals(9999, props.getInt(PropertiesConstant.SERVER_PORT));

        when(env.getProperty(PropertiesConstant.SERVER_PORT)).thenReturn("8888");
        when(env.getProperty(PropertiesConstant.HTTP_MAX_IN_MEMORY_SIZE)).thenReturn("2048");
        assertEquals(9999, props.getInt(PropertiesConstant.SERVER_PORT), "刷新前为旧值");

        webContext.refreshProperties();

        assertEquals(8888, props.getInt(PropertiesConstant.SERVER_PORT), "map 缓存应失效");
        assertEquals(2048, props.getMaxInMemorySize(), "热字段快照应随刷新更新");
    }
}
