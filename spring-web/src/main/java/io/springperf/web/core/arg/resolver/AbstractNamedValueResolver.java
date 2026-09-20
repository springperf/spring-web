package io.springperf.web.core.arg.resolver;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.databinder.WebDataBinderRegistry;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.util.MetaUtils;
import org.springframework.core.MethodParameter;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.lang.Nullable;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.Map;

public abstract class AbstractNamedValueResolver extends AbstractSupportOptionalResolver implements StaticArgumentResolver {

    protected final WebContext webContext;
    protected final String name;

    protected final WebDataBinderRegistry webDataBinderRegistry;

    /**
     * 参数类型能否直接接受 String 且非容器（启动期预判）。
     * <p>命名参数（{@code @RequestParam}/{@code @RequestHeader}/{@code @PathVariable}）的原始取值恒为
     * String，故该判定为真时无需任何转换 —— 热路径可跳过每参数的类型判定（见 {@link #convert(Object)}）。</p>
     */
    private final boolean stringAssignableWithoutConversion;

    public AbstractNamedValueResolver(WebContext webContext, MappingHandlerMethod mappingContext, MethodParameter parameter, Class<? extends Annotation>... supportClass) {
        super(mappingContext, parameter);
        this.webContext = webContext;
        this.name = MetaUtils.getParameterName(parameter, supportClass);
        this.webDataBinderRegistry = webContext.getWebComponent(WebDataBinderRegistry.class);
        this.stringAssignableWithoutConversion = isStringAssignableWithoutConversion(this.paramType);
    }

    public AbstractNamedValueResolver(MappingHandlerMethod mappingContext, MethodParameter parameter, WebContext webContext, String name) {
        super(mappingContext, parameter);
        this.webContext = webContext;
        this.name = name;
        this.webDataBinderRegistry = webContext.getWebComponent(WebDataBinderRegistry.class);
        this.stringAssignableWithoutConversion = isStringAssignableWithoutConversion(this.paramType);
    }

    private static boolean isStringAssignableWithoutConversion(Class<?> paramType) {
        return paramType != null
                && paramType.isAssignableFrom(String.class)
                && !Collection.class.isAssignableFrom(paramType)
                && !Map.class.isAssignableFrom(paramType)
                && !paramType.isArray();
    }

    protected abstract Object resolveByName(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception;

    @Override
    protected Object doResolveArgument(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
        Object arg = resolveByName(request, response);
        if (arg == null) {
            arg = handleNullValue(name, paramType);
        } else if (isEmpty(arg)) {
            arg = handleEmptyValue(arg, name, paramType);
        }
        return convert(arg);
    }

    protected Object convert(Object arg) {
        if (arg == null) return null;
        if (arg instanceof String && stringAssignableWithoutConversion) {
            // 启动期已判定「参数类型可直接接受 String 且非容器」：命名参数的原始取值恒为 String，
            // 故直接返回，省掉每参数的 arg.getClass() + isAssignableFrom + isContainer 三处判定
            // （JFR 叶帧 AbstractNamedValueResolver.convert 81 样本 / 2195 ≈ 3.7%，为项目内首位）。
            return arg;
        }
        if (paramType.isAssignableFrom(arg.getClass()) && !isContainer(arg)) {
            return arg;
        }
        try {
            return convertWithGenericType(arg);
        } catch (ConversionException e) {
            // 对齐 Spring 语义：类型转换失败抛 MethodArgumentTypeMismatchException，
            // 由 ResponseStatusExceptionResolver 映射为 400 而非 500。
            // 修复前只 catch 子类 ConversionFailedException，漏平级 ConverterNotFoundException
            // （目标类型无 String→T 转换器）导致该错误逸出为 500；catch 父类统一收敛为 400。
            throw new MethodArgumentTypeMismatchException(arg, paramType, name, parameter, e);
        }
    }

    /**
     * Collection/Map/数组 参数：即使运行时类型与 {@code paramType} 兼容（如 List→List），
     * 泛型元素类型也可能不匹配（解析结果 {@code List<String>} → 形参 {@code List<Integer>}）。
     * 必须携带方法参数的完整泛型信息（{@link TypeDescriptor}）做元素级转换，
     * 由 ConversionService 的 CollectionToCollectionConverter / ArrayToArrayConverter 完成。
     * 修复前短路返回原集合，业务层遍历时抛 ClassCastException。
     */
    protected Object convertWithGenericType(Object arg) {
        TypeDescriptor targetType = new TypeDescriptor(parameter.nestedIfOptional());
        return webDataBinderRegistry.getConversionService(mappingContext).convert(arg, targetType);
    }

    protected boolean isContainer(Object arg) {
        return arg instanceof Collection || arg instanceof Map || arg.getClass().isArray();
    }

    /**
     * Handle a null value for the named parameter.
     * <p>A {@code null} results in a {@code false} value for {@code boolean}s or an exception for other primitives.</p>
     *
     * @param name      the parameter name
     * @param paramType the parameter type
     * @return the value to use for null (Boolean.FALSE for booleans, null for others)
     */
    @Nullable
    protected Object handleNullValue(String name, Class<?> paramType) {
        if (Boolean.TYPE.equals(paramType)) {
            return Boolean.FALSE;
        } else if (paramType.isPrimitive()) {
            throw new IllegalStateException("Optional " + paramType.getSimpleName() + " parameter '" + name + "' is present but cannot be translated into a null value due to being declared as a " + "primitive type. Consider declaring it as object wrapper for the corresponding primitive type.");
        }
        return null;
    }

    protected Object handleEmptyValue(@Nullable Object arg, String name, Class<?> paramType) {
        return arg;
    }

    protected boolean isEmpty(Object arg) {
        return arg == null || "".equals(arg);
    }
}
