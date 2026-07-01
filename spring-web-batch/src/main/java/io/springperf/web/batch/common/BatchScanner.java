package io.springperf.web.batch.common;

import io.springperf.web.batch.annotation.BatchMapping;
import io.springperf.web.core.mapping.PathMappingContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class BatchScanner {

    public List<BatchHandlerRegistration> scan(ApplicationContext ctx,
                                                List<PathMappingContext> allMappings) {
        Map<Object, List<PathMappingContext>> byBean = allMappings.stream()
                .collect(Collectors.groupingBy(PathMappingContext::getBean));

        List<BatchMethodCandidate> candidates = findBatchMethods(ctx);
        List<BatchHandlerRegistration> result = new ArrayList<>();

        for (BatchMethodCandidate c : candidates) {
            BatchMapping ann = c.annotation;
            String singleName = resolveSingleMethodName(ann, c.method);
            PathMappingContext singleCtx = findSingleMethod(c.bean, byBean, singleName, c.method);

            Class<? extends BatchRequest<?>> requestType = resolveRequestType(c.method);
            if (requestType == null) {
                throw new IllegalStateException(
                        "@BatchMapping method " + c.method.toGenericString()
                                + " must have a List<? extends BatchRequest<?>> as its first parameter");
            }
            if (c.method.getParameterCount() != 1) {
                throw new IllegalStateException(
                        "@BatchMapping method " + c.method.toGenericString()
                                + " must have exactly one parameter (List<? extends BatchRequest<?>>), "
                                + "but found " + c.method.getParameterCount() + " parameters");
            }

            String queueName = resolveQueueName(c.beanType, c.method);
            int consumerSize = resolveConsumerSize(ann, c.method);
            Constructor<?> singleMethodCtor = resolveSingleMethodCtor(requestType, singleCtx);

            BatchRequestMetaData meta = new BatchRequestMetaData(
                    c.method, c.beanType, requestType, queueName,
                    ann.ringBufferSize(), ann.waitStrategy(), ann.backpressure(),
                    singleMethodCtor,
                    ann.maxBatchSize(), consumerSize
            );

            result.add(new BatchHandlerRegistration(c.bean, singleCtx, meta));
            log.info("Batch queue [{}] registered: batchMethod={}, singleMethod={}, requestType={}",
                    queueName, c.method.getName(), singleName, requestType.getSimpleName());
        }

        log.info("Discovered {} @BatchMapping methods", result.size());
        return result;
    }

    // ------------------------------------------------------------------
    // Finding @BatchMapping methods
    // ------------------------------------------------------------------

    private List<BatchMethodCandidate> findBatchMethods(ApplicationContext ctx) {
        Map<String, Object> beans = ctx.getBeansWithAnnotation(Controller.class);

        List<BatchMethodCandidate> result = new ArrayList<>();
        Set<Object> seen = new HashSet<>();

        for (Object bean : beans.values()) {
            if (!seen.add(bean)) continue;
            Class<?> targetClass = ClassUtils.getUserClass(bean.getClass());

            for (Method method : targetClass.getDeclaredMethods()) {
                BatchMapping ann = AnnotatedElementUtils.findMergedAnnotation(method, BatchMapping.class);
                if (ann != null) {
                    method.setAccessible(true);
                    result.add(new BatchMethodCandidate(bean, targetClass, method, ann));
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Single method resolution
    // ------------------------------------------------------------------

    private String resolveSingleMethodName(BatchMapping ann, Method batchMethod) {
        return ann.method().isEmpty() ? batchMethod.getName() : ann.method();
    }

    private PathMappingContext findSingleMethod(Object bean,
                                                 Map<Object, List<PathMappingContext>> byBean,
                                                 String methodName,
                                                 Method batchMethod) {
        List<PathMappingContext> beanMappings = byBean.get(bean);
        if (beanMappings == null) {
            throw new IllegalStateException(
                    "Cannot find single-request method '" + methodName
                            + "': no mappings for this bean. @BatchMapping on " + batchMethod.getName());
        }

        List<PathMappingContext> matched = new ArrayList<>();
        for (PathMappingContext ctx : beanMappings) {
            if (ctx.getBridgedMethod().getName().equals(methodName)) {
                matched.add(ctx);
            }
        }

        if (matched.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot find single-request method '" + methodName
                            + "' for @BatchMapping on " + batchMethod.getName());
        }

        Method first = matched.get(0).getBridgedMethod();
        for (int i = 1; i < matched.size(); i++) {
            if (!first.equals(matched.get(i).getBridgedMethod())) {
                throw new IllegalStateException(
                        "Found " + matched.size() + " distinct methods named '" + methodName
                                + "' for @BatchMapping on " + batchMethod.getName()
                                + ". Expected exactly one.");
            }
        }
        return matched.get(0);
    }

    // ------------------------------------------------------------------
    // Queue name
    // ------------------------------------------------------------------

    private static String resolveQueueName(Class<?> beanType, Method batchMethod) {
        return "batch:" + beanType.getSimpleName() + "." + batchMethod.getName();
    }

    // ------------------------------------------------------------------
    // Request type from batch method's List<X> parameter
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Class<? extends BatchRequest<?>> resolveRequestType(Method batchMethod) {
        Type[] genericTypes = batchMethod.getGenericParameterTypes();
        if (genericTypes.length == 0) return null;

        Type type = genericTypes[0];
        if (!(type instanceof ParameterizedType)) return null;

        ParameterizedType pType = (ParameterizedType) type;
        Type raw = pType.getRawType();
        if (!(raw instanceof Class) || !List.class.isAssignableFrom((Class<?>) raw)) return null;

        Type[] args = pType.getActualTypeArguments();
        if (args.length > 0 && args[0] instanceof Class) {
            Class<?> arg = (Class<?>) args[0];
            if (BatchRequest.class.isAssignableFrom(arg)) {
                return (Class<? extends BatchRequest<?>>) arg;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Consumer size
    // ------------------------------------------------------------------

    private int resolveConsumerSize(BatchMapping ann, Method batchMethod) {
        int consumerSize = ann.consumerSize();
        return consumerSize > 0 ? consumerSize : Runtime.getRuntime().availableProcessors();
    }

    // ------------------------------------------------------------------
    // Param name extraction
    // ------------------------------------------------------------------

    private Constructor<?> resolveSingleMethodCtor(Class<?> requestType, PathMappingContext singleCtx) {
        MethodParameter[] params = singleCtx.getMethodParameters();
        Class<?>[] paramTypes = params == null || params.length == 0
                ? new Class<?>[0]
                : Arrays.stream(params).map(MethodParameter::getParameterType).toArray(Class<?>[]::new);

        try {
            Constructor<?> ctor = requestType.getDeclaredConstructor(paramTypes);
            ctor.setAccessible(true);
            return ctor;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "BatchRequest [" + requestType.getSimpleName()
                            + "] must have a constructor matching method ["
                            + singleCtx.getBridgedMethod().getName() + "] parameter types: "
                            + Arrays.toString(paramTypes));
        }
    }

    // ------------------------------------------------------------------
    // Internal candidates
    // ------------------------------------------------------------------

    private static class BatchMethodCandidate {
        final Object bean;
        final Class<?> beanType;
        final Method method;
        final BatchMapping annotation;

        BatchMethodCandidate(Object bean, Class<?> beanType, Method method, BatchMapping annotation) {
            this.bean = bean;
            this.beanType = beanType;
            this.method = method;
            this.annotation = annotation;
        }
    }
}