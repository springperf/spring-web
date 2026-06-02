package io.springperf.web.core.invoker;

import lombok.SneakyThrows;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Type;
import org.springframework.cglib.core.ReflectUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.asm.Opcodes.*;

public class FastInvokerGenerator {

    private static final AtomicInteger CLASS_COUNTER = new AtomicInteger();

    private static final Map<Method, Class<?>> invokerClassCache = new ConcurrentHashMap<>();

    public static Invoker createInvoker(Object controller, Class controllerClass, Method method) throws Throwable {
        Class<?> invokerClass = invokerClassCache.computeIfAbsent(method, (m) -> generateClass(controllerClass, m));
        Constructor<?> ctor = invokerClass.getConstructor(controllerClass);
        return (Invoker) ctor.newInstance(controller);
    }

    private static Class<?> generateClass(Class controllerClass, Method method) {
        String className = Invoker.class.getName() + "$" + controllerClass.getSimpleName()
                + "$" + method.getName() + "$" + CLASS_COUNTER.getAndIncrement();
        byte[] bytes = generateBytes(controllerClass, method, className);
        Class<?> invokerClass = defineClass(className, bytes, Invoker.class);
        return invokerClass;
    }

    private static byte[] generateBytes(Class<?> controllerClass, Method method, String className) {
        String invokerInternal = className.replace(".", "/");
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V1_8, ACC_PUBLIC | ACC_FINAL, invokerInternal, null, "java/lang/Object", new String[]{Type.getInternalName(Invoker.class)});
        // ---------- field: private final Controller target ----------
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "target", Type.getDescriptor(controllerClass), null, null).visitEnd();
        // ---------- constructor ----------
        generateConstructor(cw, invokerInternal, controllerClass);
        // ---------- invoke method ----------
        generateInvokeMethod(cw, invokerInternal, controllerClass, method);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void generateConstructor(ClassWriter cw, String invokerInternal, Class<?> controllerClass) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + Type.getDescriptor(controllerClass) + ")V", null, null);
        mv.visitCode();
        // super();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        // this.target = arg0;
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(PUTFIELD, invokerInternal, "target", Type.getDescriptor(controllerClass));
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void generateInvokeMethod(ClassWriter cw, String invokerInternal, Class<?> controllerClass, Method method) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "invoke", "([Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
        mv.visitCode();
        // load this.target
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, invokerInternal, "target", Type.getDescriptor(controllerClass));

        // load parameters from args[]
        Class<?>[] paramTypes = method.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            mv.visitVarInsn(ALOAD, 1); // args
            pushInt(mv, i);
            mv.visitInsn(AALOAD);
            Class<?> p = paramTypes[i];
            Type pt = Type.getType(p);
            if (p.isPrimitive()) {
                unbox(mv, pt);
            } else {
                mv.visitTypeInsn(CHECKCAST, pt.getInternalName());
            }
        }
        // invoke target method
        mv.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(controllerClass), method.getName(), Type.getMethodDescriptor(method), false);

        // handle return
        Type rt = Type.getReturnType(method);
        if (rt.equals(Type.VOID_TYPE)) {
            mv.visitInsn(ACONST_NULL);
        } else if (rt.getSort() < Type.ARRAY) {
            box(mv, rt);
        }
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void pushInt(MethodVisitor mv, int i) {
        if (i >= -1 && i <= 5) {
            mv.visitInsn(ICONST_0 + i);
        } else {
            mv.visitIntInsn(BIPUSH, i);
        }
    }

    private static void unbox(MethodVisitor mv, Type type) {
        switch (type.getSort()) {
            case Type.INT:
                mv.visitTypeInsn(CHECKCAST, "java/lang/Integer");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
                break;
            case Type.BOOLEAN:
                mv.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                break;
            case Type.LONG:
                mv.visitTypeInsn(CHECKCAST, "java/lang/Long");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false);
                break;
            case Type.DOUBLE:
                mv.visitTypeInsn(CHECKCAST, "java/lang/Double");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false);
                break;
            case Type.FLOAT:
                mv.visitTypeInsn(CHECKCAST, "java/lang/Float");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false);
                break;
            case Type.SHORT:
                mv.visitTypeInsn(CHECKCAST, "java/lang/Short");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false);
                break;
            case Type.BYTE:
                mv.visitTypeInsn(CHECKCAST, "java/lang/Byte");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false);
                break;
            case Type.CHAR:
                mv.visitTypeInsn(CHECKCAST, "java/lang/Character");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
                break;
            default:
                throw new IllegalStateException("Unsupported primitive: " + type);
        }
    }

    private static void box(MethodVisitor mv, Type type) {
        switch (type.getSort()) {
            case Type.INT:
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                break;
            case Type.BOOLEAN:
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
                break;
            case Type.LONG:
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
                break;
            case Type.DOUBLE:
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
                break;
            case Type.FLOAT:
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
                break;
            case Type.SHORT:
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
                break;
            case Type.BYTE:
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
                break;
            case Type.CHAR:
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
                break;
        }
    }

    @SneakyThrows
    public static Class defineClass(String className, byte[] bytecode, Class contextClass) {
        return ReflectUtils.defineClass(className, bytecode, contextClass.getClassLoader(), null, contextClass);
    }
}
