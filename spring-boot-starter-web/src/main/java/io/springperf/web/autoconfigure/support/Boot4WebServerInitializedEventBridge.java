package io.springperf.web.autoconfigure.support;

import io.springperf.web.server.NettyHttpServer;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.FieldVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.context.ApplicationContext;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;

/**
 * Spring Boot 4 专用桥接：反射 + Spring 自带 ASM（{@code org.springframework.asm}）运行时
 * 生成 {@code WebServerInitializedEvent} 的具体子类。
 *
 * <p>SB4 中该事件移到 {@code org.springframework.boot.web.server.context} 包且为抽象类，
 * 仅 {@code ServletWebServerInitializedEvent}/{@code ReactiveWebServerInitializedEvent}
 * 两个具体子类、构造参数绑定 servlet/reactive 上下文，无法直接反射实例化。
 * 本桥接编译期零引用任何 SB4 类（保持"编译一次、发布不变"），运行时按名称反射加载：
 * 用 {@code org.springframework.asm.ClassWriter} 生成覆盖 {@code getApplicationContext()}
 * 的具体子类字节码，经 {@link MethodHandles.Lookup#defineClass} 定义后反射实例化；
 * 上下文用 JDK 动态代理包装真实 {@link ApplicationContext} 为 SB4 的
 * {@code WebServerApplicationContext} 接口。全程零新增依赖（ASM 由 spring-core 提供）。
 *
 * <p>仅在 SB4 环境由 {@link io.springperf.web.autoconfigure.Boot4WebServerInitializedEventAutoConfiguration}
 * 触发，SB3 下类不加载。
 */
public final class Boot4WebServerInitializedEventBridge {

    /** SB4 事件抽象类（新包名，SB3 不存在） */
    private static final String SB4_EVENT_CLASS = "org.springframework.boot.web.server.context.WebServerInitializedEvent";
    /** SB4 上下文接口（新包名） */
    private static final String SB4_CONTEXT_IFACE = "org.springframework.boot.web.server.context.WebServerApplicationContext";
    /** WebServer 接口（SB3/SB4 包名一致） */
    private static final String SB4_WEBSERVER_IFACE = "org.springframework.boot.web.server.WebServer";

    private static final String GENERATED_NAME = "io.springperf.web.autoconfigure.support.GeneratedWebServerInitializedEvent";
    private static final String GENERATED_INTERNAL_NAME = GENERATED_NAME.replace('.', '/');
    private static final String EVENT_INTERNAL_NAME = SB4_EVENT_CLASS.replace('.', '/');
    private static final String WEBSERVER_DESC = "L" + SB4_WEBSERVER_IFACE.replace('.', '/') + ";";
    private static final String CONTEXT_DESC = "L" + SB4_CONTEXT_IFACE.replace('.', '/') + ";";
    private static final String CTOR_DESC = "(" + WEBSERVER_DESC + CONTEXT_DESC + ")V";
    private static final String SUPER_CTOR_DESC = "(" + WEBSERVER_DESC + ")V";

    /** 缓存生成的子类字节码（仅生成一次，避免重复 ASM 开销） */
    private static volatile byte[] generatedBytes;
    /** 缓存生成的子类 Class（仅 defineClass 一次，第二次抛 LinkageError） */
    private static volatile Class<?> generatedEventClass;

    private Boot4WebServerInitializedEventBridge() {
    }

    /**
     * 生成具体事件并发布。调用方（配置类）应捕获 Throwable 降级，不影响应用启动。
     */
    public static void publish(NettyHttpServer nettyHttpServer, ApplicationContext applicationContext) throws Exception {
        // 门卫：SB4 事件类不存在（如 SB3 误触）时抛出，由调用方捕获降级
        Class.forName(SB4_EVENT_CLASS);
        Class<?> contextInterface = Class.forName(SB4_CONTEXT_IFACE);
        Class<?> webServerInterface = Class.forName(SB4_WEBSERVER_IFACE);

        PerfWebServer webServer = new PerfWebServer(nettyHttpServer.getActualPort(), nettyHttpServer);
        Object contextProxy = createContextProxy(contextInterface, webServer, applicationContext);
        Object event = createEvent(contextInterface, webServerInterface, webServer, contextProxy);

        applicationContext.publishEvent(event);
    }

    /** JDK 动态代理：真实 ApplicationContext 包装为 SB4 WebServerApplicationContext 接口 */
    private static Object createContextProxy(Class<?> contextInterface, PerfWebServer webServer,
                                             ApplicationContext delegate) {
        return Proxy.newProxyInstance(
                contextInterface.getClassLoader(),
                new Class<?>[]{contextInterface},
                (proxy, method, args) -> {
                    if ("getWebServer".equals(method.getName()) && method.getParameterCount() == 0) {
                        return webServer;
                    }
                    if ("getServerNamespace".equals(method.getName()) && method.getParameterCount() == 0) {
                        return null;
                    }
                    return method.invoke(delegate, args);
                });
    }

    /** 生成子类字节码并定义到桥接所在包/类加载器，再反射实例化 */
    private static Object createEvent(Class<?> contextInterface, Class<?> webServerInterface,
                                      PerfWebServer webServer, Object contextProxy) throws Exception {
        // C6：defineClass 幂等瓶颈——同一名称的类只能定义一次，并发二次 define 抛 LinkageError。
        // generatedBytes 的 volatile 双检锁只保证字节码生成一次，无法阻止两个线程同时进入
        // defineClass（第二个线程读到的 generatedEventClass 仍为 null 时也会 define）。
        // 故对"生成字节码 + defineClass + 发布"整体加锁，使并发发布退化为单次定义。
        Class<?> generated = generatedEventClass;
        if (generated == null) {
            synchronized (Boot4WebServerInitializedEventBridge.class) {
                generated = generatedEventClass;
                if (generated == null) {
                    byte[] bytes = generatedBytes;
                    if (bytes == null) {
                        bytes = generateEventSubclass();
                        generatedBytes = bytes;
                    }
                    // defineClass 要求生成类与 Lookup 所在类同包；GENERATED_NAME 与桥接同包，满足
                    generated = MethodHandles.lookup().defineClass(bytes);
                    generatedEventClass = generated;
                }
            }
        }
        Constructor<?> constructor = generated.getDeclaredConstructor(webServerInterface, contextInterface);
        return constructor.newInstance(webServer, contextProxy);
    }

    /** 用 spring-core 内嵌 ASM 生成具体子类：构造器 (WebServer, WebServerApplicationContext)，getApplicationContext() 返回上下文 */
    private static byte[] generateEventSubclass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                // COMPUTE_FRAMES 解析被引用类型时用桥接类加载器，避免依赖线程上下文 CL
                ClassLoader cl = Boot4WebServerInitializedEventBridge.class.getClassLoader();
                try {
                    Class<?> c = Class.forName(type1.replace('/', '.'), false, cl);
                    Class<?> d = Class.forName(type2.replace('/', '.'), false, cl);
                    if (c.isAssignableFrom(d)) {
                        return type1;
                    }
                    if (d.isAssignableFrom(c)) {
                        return type2;
                    }
                    if (c.isInterface() || d.isInterface()) {
                        return "java/lang/Object";
                    }
                    do {
                        c = c.getSuperclass();
                    } while (!c.isAssignableFrom(d));
                    return c.getName().replace('.', '/');
                } catch (Exception ex) {
                    throw new IllegalStateException("Failed to resolve ASM common superclass " + type1 + " / " + type2, ex);
                }
            }
        };

        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, GENERATED_INTERNAL_NAME,
                null, EVENT_INTERNAL_NAME, null);

        FieldVisitor fv = cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "applicationContext", CONTEXT_DESC, null, null);
        fv.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", CTOR_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, EVENT_INTERNAL_NAME, "<init>", SUPER_CTOR_DESC, false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.PUTFIELD, GENERATED_INTERNAL_NAME, "applicationContext", CONTEXT_DESC);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getApplicationContext", "()" + CONTEXT_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, GENERATED_INTERNAL_NAME, "applicationContext", CONTEXT_DESC);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
