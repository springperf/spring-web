package io.springperf.web.autoconfigure.openapi;

import io.springperf.web.context.WebContext;
import io.springperf.web.util.WebUtils;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.match.HttpMethodMatcher;
import io.springperf.web.core.mapping.match.Matcher;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 将框架 {@link MappingRegistry} 中的路由暴露到 SpringDoc OpenAPI 文档。
 * <p>
 * 工作原理：遍历 {@link PathMappingContext} 列表，提取路径、HTTP 方法、 参数和返回值信息，构建 Swagger {@link PathItem} / {@link Operation} 对象， 通过
 * {@link org.springdoc.core.customizers.OpenApiCustomizer} 注入 OpenAPI 文档。
 * </p>
 * <p>
 * 用户只需在项目中添加 {@code springdoc-openapi-ui} 依赖， 本框架的 {@code OpenApiAutoConfiguration} 会自动注册此适配器。
 * </p>
 *
 * @author huangcanda
 *
 * @since 1.0.4
 *
 * @see io.springperf.web.autoconfigure.OpenApiAutoConfiguration
 */
public class OpenApiAdapter {

    private final WebContext webContext;

    public OpenApiAdapter(WebContext webContext) {
        this.webContext = webContext;
    }

    /**
     * 从 MappingRegistry 读取路由并写入 OpenAPI 文档。
     */
    public void customize(OpenAPI openApi) {
        MappingRegistry registry = webContext.getWebComponent(MappingRegistry.class);
        if (registry == null)
            return;

        List<PathMappingContext> mappings = registry.getMappingContextList();
        if (mappings == null || mappings.isEmpty())
            return;

        Set<String> tagNames = new LinkedHashSet<>();
        // OpenAPI 要求 operationId 全局唯一；按请求重算而非放进字段，保证多次 customize 结果稳定
        Set<String> usedOperationIds = new LinkedHashSet<>();
        String contextPath = resolveContextPath();

        for (PathMappingContext ctx : mappings) {
            String rawPath = ctx.getPathRule();
            if (rawPath == null || rawPath.isEmpty())
                continue;

            String path = cleanPathForOpenApi(rawPath);
            if (contextPath != null) {
                path = contextPath + path;
            }

            Set<HttpMethod> httpMethods = extractHttpMethods(ctx);
            if (httpMethods.isEmpty())
                continue;

            String tagName = extractTagName(ctx);
            tagNames.add(tagName);

            Method method = ctx.getMethod();
            method = AopUtils.getMostSpecificMethod(method, ctx.getBeanType());

            for (HttpMethod httpMethod : httpMethods) {
                Operation operation = buildOperation(ctx, method, tagName, usedOperationIds);
                addPathParameters(path, operation);

                if (method != null) {
                    addMethodParameters(method, operation);
                    addResponse(method, operation);
                }

                PathItem pathItem = openApi.getPaths() != null ? openApi.getPaths().get(path) : null;
                if (pathItem == null) {
                    pathItem = new PathItem();
                }

                setPathItemOperation(pathItem, httpMethod, operation);
                openApi.path(path, pathItem);
            }
        }

        for (String tagName : tagNames) {
            openApi.addTagsItem(new Tag().name(tagName));
        }
    }

    /**
     * 解析 context-path 前缀，用于保证文档中的路径与真实对外 URL 一致（{@code OpenAPI} 的 {@code paths} 必须是完整路径）。
     *
     * @return 归一化后的前缀（无尾斜杠），无需前缀时返回 null；同时兼容 contextPath 本身为 "/" 或空串的情形
     */
    private String resolveContextPath() {
        String contextPath = webContext.getContextPath();
        if (contextPath == null || contextPath.isEmpty()) {
            return null;
        }
        contextPath = WebUtils.formatPath(contextPath);
        return contextPath.isEmpty() ? null : contextPath;
    }

    /**
     * 清理路径使其兼容 OpenAPI 语法：
     * <ul>
     * <li>{@code {name:\\d+}} → {@code {name}}（去掉正则约束）</li>
     * <li>{@code **} → {@code {**}}（通配符映射为 OpenAPI 的 any 参数）</li>
     * <li>{@code *} → 移除星号本身，但保留其后内容；并把连续斜杠合并为一个</li>
     * </ul>
     * <p>
     * 星号之后的残余内容必须保留：早期实现在首个星号处整段截断，会把「同一前缀 + 中间含星号 + 不同后缀」的多条路由 清洗成同一个前缀，导致它们在文档中合并成一条。
     * </p>
     */
    static String cleanPathForOpenApi(String rawPath) {
        String path = rawPath;
        // {name:regex} → {name}
        path = path.replaceAll("\\{(\\w+):[^}]+\\}", "{$1}");
        // trailing ** → /{any}
        if (path.endsWith("/**")) {
            path = path.substring(0, path.length() - 3) + "/{any}";
        } else if (path.endsWith("/*")) {
            path = path.substring(0, path.length() - 2);
        }
        // remove bare * (non-wildcard stars)
        int starIdx = path.indexOf('*');
        if (starIdx >= 0) {
            path = path.replace("*", "");
            path = path.replaceAll("/{2,}", "/");
        }
        return path;
    }

    private Set<HttpMethod> extractHttpMethods(PathMappingContext ctx) {
        Set<HttpMethod> methods = new LinkedHashSet<>();
        for (Matcher matcher : ctx.getMatchers()) {
            if (matcher instanceof HttpMethodMatcher) {
                methods.addAll(((HttpMethodMatcher) matcher).getHttpMethods());
            }
        }
        if (methods.isEmpty()) {
            methods.add(HttpMethod.GET);
        }
        return methods;
    }

    private String extractTagName(PathMappingContext ctx) {
        Class<?> beanType = ctx.getBeanType();
        if (beanType != null) {
            return beanType.getSimpleName().replace("Controller", "");
        }
        return "Endpoints";
    }

    private Operation buildOperation(PathMappingContext ctx, Method method, String tagName,
            Set<String> usedOperationIds) {
        Operation operation = new Operation();
        if (method != null) {
            operation.setOperationId(uniqueOperationId(method.getName(), usedOperationIds));
            operation.setSummary(method.getName());
            operation.setDescription(ctx.getPathRule());
        }
        operation.addTagsItem(tagName);
        operation.setResponses(new ApiResponses());
        return operation;
    }

    /**
     * 生成全局唯一的 operationId。裸方法名在不同 Controller 之间会重复（同一接口多个实现、通用 CRUD
     * Controller 等），而 OpenAPI 规范要求 operationId 唯一，重复会让代码生成器产出互相覆盖的方法。
     * 首次出现保持原方法名（不无故改变既有文档），重复时追加自增后缀。
     */
    private static String uniqueOperationId(String methodName, Set<String> usedOperationIds) {
        String id = methodName;
        int suffix = 2;
        while (!usedOperationIds.add(id)) {
            id = methodName + "_" + suffix;
            suffix++;
        }
        return id;
    }

    private void addPathParameters(String path, Operation operation) {
        int start = path.indexOf('{');
        while (start >= 0) {
            int end = path.indexOf('}', start);
            if (end < 0)
                break;

            String paramName = path.substring(start + 1, end);
            // 处理 {name:\\d+} 格式：提取 name，去掉正则部分
            int colonIdx = paramName.indexOf(':');
            if (colonIdx > 0) {
                paramName = paramName.substring(0, colonIdx);
            }
            operation.addParametersItem(
                    new Parameter().name(paramName).in("path").required(true).schema(new Schema<>().type("string")));

            start = path.indexOf('{', end + 1);
        }
    }

    private void addMethodParameters(Method method, Operation operation) {
        java.lang.reflect.Parameter[] params = method.getParameters();
        for (java.lang.reflect.Parameter param : params) {
            if (isFrameworkType(param.getType()))
                continue;

            if (param.getAnnotation(org.springframework.web.bind.annotation.PathVariable.class) != null) {
                continue;
            }

            org.springframework.web.bind.annotation.RequestParam reqParam = param
                    .getAnnotation(org.springframework.web.bind.annotation.RequestParam.class);
            if (reqParam != null) {
                String name = reqParam.value().isEmpty() ? param.getName() : reqParam.value();
                operation.addParametersItem(new Parameter().name(name).in("query").required(reqParam.required())
                        .schema(resolveSchema(param.getType())));
                continue;
            }

            org.springframework.web.bind.annotation.RequestHeader reqHeader = param
                    .getAnnotation(org.springframework.web.bind.annotation.RequestHeader.class);
            if (reqHeader != null) {
                String name = reqHeader.value().isEmpty() ? param.getName() : reqHeader.value();
                operation.addParametersItem(new Parameter().name(name).in("header").required(reqHeader.required())
                        .schema(resolveSchema(param.getType())));
                continue;
            }

            org.springframework.web.bind.annotation.ModelAttribute modelAttr = param
                    .getAnnotation(org.springframework.web.bind.annotation.ModelAttribute.class);
            if (modelAttr != null) {
                addModelAttributeParameters(param, operation);
                continue;
            }

            org.springframework.web.bind.annotation.RequestBody reqBody = param
                    .getAnnotation(org.springframework.web.bind.annotation.RequestBody.class);
            if (reqBody != null) {
                operation.setRequestBody(new io.swagger.v3.oas.models.parameters.RequestBody()
                        .content(new Content().addMediaType("application/json",
                                new MediaType().schema(resolveRequestSchema(param))))
                        .required(reqBody.required()));
                continue;
            }

            if (isSimpleType(param.getType())) {
                operation.addParametersItem(new Parameter().name(param.getName()).in("query").required(false)
                        .schema(resolveSchema(param.getType())));
            }
        }
    }

    /**
     * 解析请求体 schema：对 {@code List<Dto>} 之类的泛型取类型实参生成 {@code array} 及其元素结构， 其余按 {@link #resolveSchema} 展开。
     */
    private static Schema<?> resolveRequestSchema(java.lang.reflect.Parameter param) {
        Type genericType = param.getParameterizedType();
        if (genericType instanceof ParameterizedType) {
            Type[] args = ((ParameterizedType) genericType).getActualTypeArguments();
            if (args.length == 1 && args[0] instanceof Class<?> elementType) {
                return new Schema<>().type("array").items(resolveSchema(elementType, new LinkedHashSet<>()));
            }
        }
        return resolveSchema(param.getType());
    }

    /**
     * 展开 {@code @ModelAttribute} 参数：Spring 的数据绑定按属性逐个绑定，文档也应逐属性列出 query 参数， 而不是塞一个 {@code object}
     * 类型的同名参数（客户端无从得知该传什么）。
     * <p>
     * 载体没有任何可绑定属性时退化为单个 object 参数，保持既有输出形态。
     * </p>
     */
    private static void addModelAttributeParameters(java.lang.reflect.Parameter param, Operation operation) {
        Map<String, Schema> properties = resolveProperties(param.getType(), new LinkedHashSet<>());
        if (properties == null || properties.isEmpty()) {
            operation.addParametersItem(
                    new Parameter().name(param.getName()).in("query").schema(resolveSchema(param.getType())));
            return;
        }
        for (Map.Entry<String, Schema> property : properties.entrySet()) {
            operation.addParametersItem(
                    new Parameter().name(property.getKey()).in("query").required(false).schema(property.getValue()));
        }
    }
    private void addResponse(Method method, Operation operation) {
        // 从 @ResponseStatus 读取实际状态码与其 reason（含 Spring 的默认 reason phrase），默认 200 OK。
        // 早期实现丢弃 reason 并对所有非 204 状态一律写 "OK"，201/202/204 的文档描述因此与实际不符。
        HttpStatus status = HttpStatus.OK;
        String reason = null;
        if (method != null) {
            org.springframework.web.bind.annotation.ResponseStatus rs = AnnotatedElementUtils
                    .findMergedAnnotation(method, org.springframework.web.bind.annotation.ResponseStatus.class);
            if (rs != null) {
                status = rs.code();
                if (!rs.reason().isEmpty()) {
                    reason = rs.reason();
                }
            }
        }
        int statusCode = status.value();
        String description = reason != null ? reason : status.getReasonPhrase();

        Class<?> returnType = resolveReturnType(method);

        if (returnType == void.class || returnType == Void.class) {
            operation.getResponses().addApiResponse(String.valueOf(statusCode),
                    new ApiResponse().description(description));
            return;
        }

        Schema<?> schema = resolveSchema(returnType);
        ApiResponse response = new ApiResponse().description(description);

        if (method.isAnnotationPresent(org.springframework.web.bind.annotation.ResponseBody.class)
                || method.getDeclaringClass()
                        .isAnnotationPresent(org.springframework.web.bind.annotation.RestController.class)) {
            response.setContent(new Content().addMediaType("application/json", new MediaType().schema(schema)));
        }

        operation.getResponses().addApiResponse(String.valueOf(statusCode), response);
    }

    /**
     * 解析方法的实际返回值类型，对 CompletableFuture / Future 解包泛型参数。
     */
    static Class<?> resolveReturnType(Method method) {
        Class<?> returnType = method.getReturnType();
        if (CompletableFuture.class.isAssignableFrom(returnType)
                || java.util.concurrent.Future.class.isAssignableFrom(returnType)) {
            Type genericReturnType = method.getGenericReturnType();
            if (genericReturnType instanceof java.lang.reflect.ParameterizedType) {
                Type[] typeArgs = ((java.lang.reflect.ParameterizedType) genericReturnType).getActualTypeArguments();
                if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                    return (Class<?>) typeArgs[0];
                }
            }
        }
        return returnType;
    }

    private void setPathItemOperation(PathItem pathItem, HttpMethod httpMethod, Operation operation) {
        if (HttpMethod.GET.equals(httpMethod)) {
            pathItem.setGet(operation);
        } else if (HttpMethod.POST.equals(httpMethod)) {
            pathItem.setPost(operation);
        } else if (HttpMethod.PUT.equals(httpMethod)) {
            pathItem.setPut(operation);
        } else if (HttpMethod.DELETE.equals(httpMethod)) {
            pathItem.setDelete(operation);
        } else if (HttpMethod.PATCH.equals(httpMethod)) {
            pathItem.setPatch(operation);
        } else if (HttpMethod.HEAD.equals(httpMethod)) {
            pathItem.setHead(operation);
        } else if (HttpMethod.OPTIONS.equals(httpMethod)) {
            pathItem.setOptions(operation);
        }
    }

    static Schema<?> resolveSchema(Class<?> type) {
        return resolveSchema(type, new LinkedHashSet<>());
    }

    private static Schema<?> resolveSchema(Class<?> type, Set<Class<?>> visiting) {
        if (type == String.class)
            return new Schema<>().type("string");
        if (type == Integer.class || type == int.class)
            return new Schema<>().type("integer").format("int32");
        if (type == Long.class || type == long.class)
            return new Schema<>().type("integer").format("int64");
        if (type == Double.class || type == double.class)
            return new Schema<>().type("number").format("double");
        if (type == Float.class || type == float.class)
            return new Schema<>().type("number").format("float");
        if (type == Boolean.class || type == boolean.class)
            return new Schema<>().type("boolean");
        if (type.isArray())
            return new Schema<>().type("array").items(resolveSchema(type.getComponentType(), visiting));
        if (Iterable.class.isAssignableFrom(type))
            return new Schema<>().type("array").items(new Schema<>().type("object"));
        Map<String, Schema> properties = resolveProperties(type, visiting);
        Schema<?> schema = new Schema<>().type("object");
        if (properties != null && !properties.isEmpty()) {
            schema.setProperties(properties);
        }
        return schema;
    }

    /**
     * 提取 JavaBean 可读属性，供请求/响应 schema 与 {@code @ModelAttribute} 参数展开复用。
     * <p>
     * 用 Spring 的 {@link org.springframework.beans.BeanUtils#getPropertyDescriptors} 而非裸反射：
     * 只有实际可绑定的属性才会进入文档，与实际数据绑定语义一致。
     * </p>
     * <p>
     * {@code visiting} 用于掐断自引用/循环引用（如双向关联的实体）；无法展开的类型返回 null。
     * </p>
     */
    private static Map<String, Schema> resolveProperties(Class<?> type, Set<Class<?>> visiting) {
        if (type == null || type.isPrimitive() || type.isArray() || type.isEnum() || Iterable.class.isAssignableFrom(type)
                || Map.class.isAssignableFrom(type) || type.getName().startsWith("java.")
                || type.getName().startsWith("javax.") || type.getName().startsWith("jakarta.")
                || visiting.contains(type)) {
            return null;
        }
        Map<String, Schema> properties = new LinkedHashMap<>();
        visiting.add(type);
        try {
            for (java.beans.PropertyDescriptor descriptor : org.springframework.beans.BeanUtils
                    .getPropertyDescriptors(type)) {
                Method readMethod = descriptor.getReadMethod();
                if (readMethod == null || readMethod.getDeclaringClass() == Object.class
                        || "class".equals(descriptor.getName())) {
                    continue;
                }
                properties.put(descriptor.getName(), resolveSchema(readMethod.getReturnType(), visiting));
            }
        } finally {
            visiting.remove(type);
        }
        return properties;
    }

    static boolean isFrameworkType(Class<?> type) {
        return type.getName().startsWith("javax.servlet") || type.getName().startsWith("jakarta.servlet")
                || type == org.springframework.http.HttpEntity.class
                || type == org.springframework.http.RequestEntity.class
                || type == org.springframework.validation.BindingResult.class || type == java.security.Principal.class;
    }

    static boolean isSimpleType(Class<?> type) {
        return type.isPrimitive() || type == String.class || type == Integer.class || type == Long.class
                || type == Double.class || type == Float.class || type == Boolean.class || type == java.util.Date.class
                || type == java.time.LocalDate.class || type == java.time.LocalDateTime.class;
    }
}
