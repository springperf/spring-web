package io.springperf.web.websocket.jsr;

import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.aot.generate.GenerationContext;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 验证 {@link ServerEndpointBeanFactoryInitializationAotProcessor}：
 * 为 Bean 发现的 {@code @ServerEndpoint} 端点注册无参构造器 + {@code @On*} 回调方法 hints。
 * JVM 模式下处理器由 Spring AOT 构建期调用，本测试直接驱动它验证注册结果。
 */
class ServerEndpointBeanFactoryInitializationAotProcessorTest {

    private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

    @Test
    void processAheadOfTime_noEndpoints_returnsNull() {
        BeanFactoryInitializationAotContribution contribution =
                new ServerEndpointBeanFactoryInitializationAotProcessor().processAheadOfTime(beanFactory);
        assertNull(contribution);
    }

    @Test
    void registersEndpointConstructorAndCallbackHints() {
        beanFactory.registerSingleton("chatEndpoint", new ChatEndpoint());

        BeanFactoryInitializationAotContribution contribution =
                new ServerEndpointBeanFactoryInitializationAotProcessor().processAheadOfTime(beanFactory);
        assertNotNull(contribution, "存在 @ServerEndpoint bean 时应产生 AOT contribution");

        RuntimeHints hints = new RuntimeHints();
        GenerationContext generationContext = mock(GenerationContext.class);
        when(generationContext.getRuntimeHints()).thenReturn(hints);
        contribution.applyTo(generationContext, null);

        // 无参构造器注册（INVOKE_DECLARED_CONSTRUCTORS 含 <init>）
        assertTrue(hints.reflection().getTypeHint(ChatEndpoint.class) != null,
                "端点类应注册反射 hint");
        assertTrue(hints.reflection().getTypeHint(ChatEndpoint.class).getMemberCategories()
                        .contains(org.springframework.aot.hint.MemberCategory.INVOKE_DECLARED_CONSTRUCTORS),
                "端点类应注册 INVOKE_DECLARED_CONSTRUCTORS");

        // @OnOpen / @OnMessage 回调方法注册 INVOKE hint
        assertMethodInvokeHint(hints, "onOpen");
        assertMethodInvokeHint(hints, "onTextMessage");
    }

    private static void assertMethodInvokeHint(RuntimeHints hints, String methodName) {
        boolean registered = hints.reflection().typeHints()
                .flatMap(org.springframework.aot.hint.TypeHint::methods)
                .map(org.springframework.aot.hint.ExecutableHint::getName)
                .anyMatch(methodName::equals);
        assertTrue(registered, "回调方法 " + methodName + " 应注册反射 hint");
    }

    @ServerEndpoint("/chat")
    static class ChatEndpoint {

        @OnOpen
        public void onOpen(Session session) {
        }

        @OnMessage
        public String onTextMessage(String message) {
            return "echo:" + message;
        }
    }
}