package io.springperf.web.core.invoker;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public class MethodHandleInvoker implements Invoker {

    private final MethodHandle methodHandle;

    public MethodHandleInvoker(MethodHandle methodHandle) {
        int paramCount = methodHandle.type().parameterCount();
        // 统一适配为 (Object[]) -> Object：
        // - 有参数：asSpreader 将 Object[] 展开为独立参数，再统一返回类型
        // - 无参数：先统一返回类型，再用 dropArguments 忽略 Object[] 参数
        if (paramCount > 0) {
            MethodHandle spread = methodHandle.asSpreader(Object[].class, paramCount);
            this.methodHandle = spread.asType(MethodType.methodType(Object.class, Object[].class));
        } else {
            MethodHandle noArg = methodHandle.asType(MethodType.methodType(Object.class));
            this.methodHandle = MethodHandles.dropArguments(noArg, 0, Object[].class);
        }
    }

    @Override
    public Object invoke(Object[] args) throws Throwable {
        return methodHandle.invokeExact(args);
    }
}
