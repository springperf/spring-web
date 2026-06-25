package io.springperf.web.autoconfigure.openapi;

import io.springperf.web.context.WebContext;
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

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 将框架 {@link MappingRegistry} 中的路由暴露到 SpringDoc OpenAPI 文档。
 *
 * <p>工作原理：遍历 {@link PathMappingContext} 列表，提取路径、HTTP 方法、
 * 参数和返回值信息，构建 Swagger {@link PathItem} / {@link Operation} 对象，
 * 通过 {@link org.springdoc.core.customizers.OpenApiCustomizer} 注入 OpenAPI 文档。</p>
 *
 * <p>用户只需在项目中添加 {@code springdoc-openapi-ui} 依赖，
 * 本框架的 {@code OpenApiAutoConfiguration} 会自动注册此适配器。</p>
 *
 * @author huangcanda
 * @since 1.0.4
 * @see OpenApiAutoConfiguration
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
        if (registry == null) return;

        List<PathMappingContext> mappings = registry.getMappingContextList();
        if (mappings == null || mappings.isEmpty()) return;

        Set<String> tagNames = new LinkedHashSet<>();

        for (PathMappingContext ctx : mappings) {
            String rawPath = ctx.getPathRule();
            if (rawPath == null || rawPath.isEmpty()) continue;

            String path = cleanPathForOpenApi(rawPath);

            Set<HttpMethod> httpMethods = extractHttpMethods(ctx);
            if (httpMethods.isEmpty()) continue;

            String tagName = extractTagName(ctx);
            tagNames.add(tagName);

            Method method = ctx.getBridgedMethod();
            method = AopUtils.getMostSpecificMethod(method, ctx.getBeanType());

            for (HttpMethod httpMethod : httpMethods) {
                Operation operation = buildOperation(ctx, method, tagName);
                addPathParameters(path, operation);

                if (method != null) {
                    addMethodParameters(method, operation);
                    addResponse(method, operation);
                }

                PathItem pathItem = openApi.getPaths() != null
                        ? openApi.getPaths().get(path) : null;
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
     * 清理路径使其兼容 OpenAPI 语法：
     * <ul>
     *   <li>{@code {name:\\d+}} → {@code {name}}（去掉正则约束）</li>
     *   <li>{@code **} → {@code {**}}（通配符映射为 OpenAPI 的 any 参数）</li>
     *   <li>{@code *} → 移除尾部星号</li>
     * </ul>
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
            path = path.substring(0, starIdx);
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
            return beanType.getSimpleName()
                    .replace("Controller", "");
        }
        return "Endpoints";
    }

    private Operation buildOperation(PathMappingContext ctx, Method method, String tagName) {
        Operation operation = new Operation();
        if (method != null) {
            operation.setOperationId(method.getName());
            operation.setSummary(method.getName());
            operation.setDescription(ctx.getPathRule());
        }
        operation.addTagsItem(tagName);
        operation.setResponses(new ApiResponses());
        return operation;
    }

    private void addPathParameters(String path, Operation operation) {
        int start = path.indexOf('{');
        while (start >= 0) {
            int end = path.indexOf('}', start);
            if (end < 0) break;

            String paramName = path.substring(start + 1, end);
            // 处理 {name:\\d+} 格式：提取 name，去掉正则部分
            int colonIdx = paramName.indexOf(':');
            if (colonIdx > 0) {
                paramName = paramName.substring(0, colonIdx);
            }
            operation.addParametersItem(new Parameter()
                    .name(paramName)
                    .in("path")
                    .required(true)
                    .schema(new Schema<>().type("string")));

            start = path.indexOf('{', end + 1);
        }
    }

    private void addMethodParameters(Method method, Operation operation) {
        java.lang.reflect.Parameter[] params = method.getParameters();
        for (java.lang.reflect.Parameter param : params) {
            if (isFrameworkType(param.getType())) continue;

            if (param.getAnnotation(org.springframework.web.bind.annotation.PathVariable.class) != null) {
                continue;
            }

            org.springframework.web.bind.annotation.RequestParam reqParam =
                    param.getAnnotation(org.springframework.web.bind.annotation.RequestParam.class);
            if (reqParam != null) {
                String name = reqParam.value().isEmpty() ? param.getName() : reqParam.value();
                operation.addParametersItem(new Parameter()
                        .name(name)
                        .in("query")
                        .required(reqParam.required())
                        .schema(resolveSchema(param.getType())));
                continue;
            }

            org.springframework.web.bind.annotation.RequestHeader reqHeader =
                    param.getAnnotation(org.springframework.web.bind.annotation.RequestHeader.class);
            if (reqHeader != null) {
                String name = reqHeader.value().isEmpty() ? param.getName() : reqHeader.value();
                operation.addParametersItem(new Parameter()
                        .name(name)
                        .in("header")
                        .required(reqHeader.required())
                        .schema(resolveSchema(param.getType())));
                continue;
            }

            org.springframework.web.bind.annotation.ModelAttribute modelAttr =
                    param.getAnnotation(org.springframework.web.bind.annotation.ModelAttribute.class);
            if (modelAttr != null) {
                operation.addParametersItem(new Parameter()
                        .name(param.getName())
                        .in("query")
                        .schema(resolveSchema(param.getType())));
                continue;
            }

            org.springframework.web.bind.annotation.RequestBody reqBody =
                    param.getAnnotation(org.springframework.web.bind.annotation.RequestBody.class);
            if (reqBody != null) {
                Schema<?> schema = resolveSchema(param.getType());
                Type genericType = param.getParameterizedType();
                if (genericType instanceof ParameterizedType) {
                    schema = new Schema<>()
                            .name(param.getName())
                            .type("object");
                }
                operation.setRequestBody(new io.swagger.v3.oas.models.parameters.RequestBody()
                        .content(new Content()
                                .addMediaType("application/json",
                                        new MediaType().schema(schema)))
                        .required(reqBody.required()));
                continue;
            }

            if (isSimpleType(param.getType())) {
                operation.addParametersItem(new Parameter()
                        .name(param.getName())
                        .in("query")
                        .required(false)
                        .schema(resolveSchema(param.getType())));
            }
        }
    }

    private void addResponse(Method method, Operation operation) {
        // 从 @ResponseStatus 读取实际状态码，默认 200
        int statusCode = resolveResponseStatus(method);

        Class<?> returnType = resolveReturnType(method);

        if (returnType == void.class || returnType == Void.class) {
            operation.getResponses().addApiResponse(String.valueOf(statusCode),
                    new ApiResponse().description(statusCode == 204 ? "No Content" : "OK"));
            return;
        }

        Schema<?> schema = resolveSchema(returnType);
        ApiResponse response = new ApiResponse().description("OK");

        if (method.isAnnotationPresent(org.springframework.web.bind.annotation.ResponseBody.class)
                || method.getDeclaringClass().isAnnotationPresent(
                org.springframework.web.bind.annotation.RestController.class)) {
            response.setContent(new Content()
                    .addMediaType("application/json",
                            new MediaType().schema(schema)));
        }

        operation.getResponses().addApiResponse(String.valueOf(statusCode), response);
    }

    /**
     * 从方法或其类上读取 @ResponseStatus 注解的状态码，不存在则返回 200。
     */
    private static int resolveResponseStatus(Method method) {
        if (method == null) return 200;
        org.springframework.web.bind.annotation.ResponseStatus rs =
                AnnotatedElementUtils.findMergedAnnotation(
                        method, org.springframework.web.bind.annotation.ResponseStatus.class);
        return rs != null ? rs.code().value() : 200;
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
                Type[] typeArgs = ((java.lang.reflect.ParameterizedType) genericReturnType)
                        .getActualTypeArguments();
                if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                    return (Class<?>) typeArgs[0];
                }
            }
        }
        return returnType;
    }

    private void setPathItemOperation(PathItem pathItem, HttpMethod httpMethod, Operation operation) {
        switch (httpMethod) {
            case GET: pathItem.setGet(operation); break;
            case POST: pathItem.setPost(operation); break;
            case PUT: pathItem.setPut(operation); break;
            case DELETE: pathItem.setDelete(operation); break;
            case PATCH: pathItem.setPatch(operation); break;
            case HEAD: pathItem.setHead(operation); break;
            case OPTIONS: pathItem.setOptions(operation); break;
            default: break;
        }
    }

    static Schema<?> resolveSchema(Class<?> type) {
        if (type == String.class) return new Schema<>().type("string");
        if (type == Integer.class || type == int.class) return new Schema<>().type("integer").format("int32");
        if (type == Long.class || type == long.class) return new Schema<>().type("integer").format("int64");
        if (type == Double.class || type == double.class) return new Schema<>().type("number").format("double");
        if (type == Float.class || type == float.class) return new Schema<>().type("number").format("float");
        if (type == Boolean.class || type == boolean.class) return new Schema<>().type("boolean");
        if (type.isArray() || Iterable.class.isAssignableFrom(type))
            return new Schema<>().type("array").items(new Schema<>().type("object"));
        return new Schema<>().type("object");
    }

    static boolean isFrameworkType(Class<?> type) {
        return type.getName().startsWith("javax.servlet")
                || type.getName().startsWith("jakarta.servlet")
                || type == org.springframework.http.HttpEntity.class
                || type == org.springframework.http.RequestEntity.class
                || type == org.springframework.validation.BindingResult.class
                || type == java.security.Principal.class;
    }

    static boolean isSimpleType(Class<?> type) {
        return type.isPrimitive()
                || type == String.class
                || type == Integer.class || type == Long.class
                || type == Double.class || type == Float.class
                || type == Boolean.class
                || type == java.util.Date.class
                || type == java.time.LocalDate.class
                || type == java.time.LocalDateTime.class;
    }
}
