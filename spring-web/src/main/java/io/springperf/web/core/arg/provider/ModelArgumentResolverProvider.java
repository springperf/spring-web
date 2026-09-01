package io.springperf.web.core.arg.provider;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.MethodArgContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.mapping.route.PathPatternRouter;
import io.springperf.web.core.model.ModelContext;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.ui.ModelMap;
import org.springframework.util.ReflectionUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.ControllerAdviceBean;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 解析 {@code Model}/{@code ModelMap}/{@code ExtendedModelMap} 参数并完成 Model 初始化
 * （Model 为请求管线一等公民，容器为 {@link ModelContext}）：
 * <ol>
 *   <li>{@code @ControllerAdvice} {@code @ModelAttribute} 方法</li>
 *   <li>局部 {@code @ModelAttribute} 方法</li>
 *   <li>合并 {@code @ModelAttribute} 参数绑定结果</li>
 *   <li>合并 {@code @PathVariable}</li>
 *   <li>合并 {@code BindingResult}</li>
 * </ol>
 */
public class ModelArgumentResolverProvider extends BaseWebComponent implements StaticArgumentResolverProvider {

    private List<ModelAttributeAdviceMethod> adviceMethods = new ArrayList<>();
    private final ConcurrentHashMap<Class<?>, List<LocalModelAttributeMethod>> localAdviceCache = new ConcurrentHashMap<>();

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        List<ControllerAdviceBean> adviceBeans = ControllerAdviceBean.findAnnotatedBeans(webContext.getCtx());
        for (ControllerAdviceBean adviceBean : adviceBeans) {
            Class<?> beanType = adviceBean.getBeanType();
            if (beanType == null) continue;
            for (Method method : beanType.getMethods()) {
                ModelAttribute ma = AnnotatedElementUtils.findMergedAnnotation(method, ModelAttribute.class);
                if (ma == null) continue;
                adviceMethods.add(new ModelAttributeAdviceMethod(adviceBean, method, ma));
            }
        }
    }

    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        Class<?> paramType = parameter.getParameterType();
        return Model.class.isAssignableFrom(paramType)
                || ModelMap.class.isAssignableFrom(paramType)
                || ExtendedModelMap.class.isAssignableFrom(paramType);
    }

    @Override
    public StaticArgumentResolver getResolver(MethodParameter parameter, MappingHandlerMethod mappingContext,
                                              WebContext webContext) {
        Class<?> controllerType = mappingContext.getBeanType();
        List<ModelAttributeAdviceMethod> applicable = new ArrayList<>(adviceMethods.size());
        for (ModelAttributeAdviceMethod advice : adviceMethods) {
            if (controllerType != null && advice.adviceBean.isApplicableToBeanType(controllerType)) {
                applicable.add(advice);
            }
        }
        List<LocalModelAttributeMethod> localMethods = null;
        if (controllerType != null) {
            Object controllerBean = mappingContext.getBean();
            localMethods = localAdviceCache.computeIfAbsent(controllerType,
                    ct -> scanLocalModelAttributes(ct, controllerBean));
        }
        return new ModelStaticArgumentResolver(applicable, localMethods);
    }

    private static List<LocalModelAttributeMethod> scanLocalModelAttributes(Class<?> controllerType, Object controllerBean) {
        List<LocalModelAttributeMethod> list = new ArrayList<>();
        for (Method method : controllerType.getMethods()) {
            ModelAttribute ma = AnnotatedElementUtils.findMergedAnnotation(method, ModelAttribute.class);
            if (ma == null) continue;
            if (AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) continue;
            list.add(new LocalModelAttributeMethod(controllerBean, method, ma));
        }
        return list.isEmpty() ? null : list;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    private static class ModelStaticArgumentResolver implements StaticArgumentResolver {

        private final List<ModelAttributeAdviceMethod> adviceMethods;
        private final List<LocalModelAttributeMethod> localMethods;
        private volatile ModelParamMeta[] modelParams;

        ModelStaticArgumentResolver(List<ModelAttributeAdviceMethod> adviceMethods,
                                     List<LocalModelAttributeMethod> localMethods) {
            this.adviceMethods = adviceMethods;
            this.localMethods = localMethods;
        }

        @Override
        public Object resolveArgument(WebServerHttpRequest request, WebServerHttpResponse response) {
            return ModelContext.getOrCreate(request);
        }

        @Override
        public void postProcess(Object[] args, MethodArgContext[] contexts, int index,
                                WebServerHttpRequest request, WebServerHttpResponse response) {
            ModelMap model = (ModelMap) args[index];

            // 1. @ControllerAdvice @ModelAttribute 方法
            for (ModelAttributeAdviceMethod advice : adviceMethods) {
                invokeModelAttribute(advice.bean, advice.method, advice.name, advice.paramCount,
                        advice.hasModelParam, advice.isVoid, model);
            }

            // 2. 局部 @ModelAttribute 方法
            if (localMethods != null) {
                for (LocalModelAttributeMethod local : localMethods) {
                    invokeModelAttribute(local.bean, local.method, local.name, local.paramCount,
                            local.hasModelParam, local.isVoid, model);
                }
            }

            // 3. 合并 @ModelAttribute 参数绑定结果
            mergeModelParams(args, contexts, index, model);

            // 4. 合并 @PathVariable
            mergePathVariables(model, request);

            // 5. 合并 BindingResult
            mergeBindingResults(model, request);
        }

        private void invokeModelAttribute(Object bean, Method method, String name, int paramCount,
                                          boolean hasModelParam, boolean isVoid, ModelMap model) {
            try {
                if (paramCount == 0) {
                    Object value = method.invoke(bean);
                    if (!isVoid && value != null) model.addAttribute(name, value);
                } else if (hasModelParam) {
                    Object value = method.invoke(bean, model);
                    if (!isVoid && value != null) model.addAttribute(name, value);
                }
            } catch (InvocationTargetException ex) {
                ReflectionUtils.rethrowRuntimeException(ex.getCause());
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException("Unable to access @ModelAttribute method '" + method.getName() + "'", ex);
            }
        }

        private void mergeModelParams(Object[] args, MethodArgContext[] contexts, int index, ModelMap model) {
            ModelParamMeta[] metas = this.modelParams;
            if (metas == null) {
                metas = buildModelParams(contexts);
                this.modelParams = metas;
            }
            for (ModelParamMeta meta : metas) {
                if (meta.index == index) continue;
                Object value = args[meta.index];
                if (value == null) continue;
                if (meta.name != null) model.addAttribute(meta.name, value);
            }
        }

        private ModelParamMeta[] buildModelParams(MethodArgContext[] ctxs) {
            List<ModelParamMeta> list = new ArrayList<>();
            for (int i = 0; i < ctxs.length; i++) {
                MethodParameter param = ctxs[i].getMethodParameter();
                ModelAttribute ma = param.getParameterAnnotation(ModelAttribute.class);
                if (ma == null) continue;
                String name = ma.name();
                if (name.isEmpty()) name = ma.value();
                if (name.isEmpty()) name = param.getParameterName();
                list.add(new ModelParamMeta(i, name));
            }
            return list.toArray(new ModelParamMeta[0]);
        }

        private void mergePathVariables(ModelMap model, WebServerHttpRequest request) {
            Map<String, String> vars = PathPatternRouter.getUriVariableMap(request);
            if (vars == null || vars.isEmpty()) return;
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                if (!model.containsKey(entry.getKey())) {
                    model.addAttribute(entry.getKey(), entry.getValue());
                }
            }
        }

        private void mergeBindingResults(ModelMap model, WebServerHttpRequest request) {
            RequestContext ctx = request.getRequestContext();
            for (Map.Entry<String, Object> entry : ctx.getAttributes().entrySet()) {
                if (entry.getValue() instanceof BindingResult) {
                    BindingResult br = (BindingResult) entry.getValue();
                    String name = br.getObjectName();
                    if (!model.containsKey(name)) {
                        model.addAttribute(name, br.getTarget());
                    }
                }
            }
        }
    }

    private static class ModelAttributeAdviceMethod {
        final ControllerAdviceBean adviceBean;
        final Object bean;
        final Method method;
        final String name;
        final int paramCount;
        final boolean hasModelParam;
        final boolean isVoid;

        ModelAttributeAdviceMethod(ControllerAdviceBean adviceBean, Method method, ModelAttribute ma) {
            this.adviceBean = adviceBean;
            this.bean = adviceBean.resolveBean();
            this.method = method;
            String n = ma.name();
            if (n.isEmpty()) n = ma.value();
            if (n.isEmpty()) n = method.getName();
            this.name = n;
            this.paramCount = method.getParameterCount();
            this.hasModelParam = paramCount == 1
                    && Model.class.isAssignableFrom(method.getParameterTypes()[0]);
            this.isVoid = method.getReturnType() == void.class;
        }
    }

    private static class LocalModelAttributeMethod {
        final Object bean;
        final Method method;
        final String name;
        final int paramCount;
        final boolean hasModelParam;
        final boolean isVoid;

        LocalModelAttributeMethod(Object bean, Method method, ModelAttribute ma) {
            this.bean = bean;
            this.method = method;
            String n = ma.name();
            if (n.isEmpty()) n = ma.value();
            if (n.isEmpty()) n = method.getName();
            this.name = n;
            this.paramCount = method.getParameterCount();
            this.hasModelParam = paramCount == 1
                    && Model.class.isAssignableFrom(method.getParameterTypes()[0]);
            this.isVoid = method.getReturnType() == void.class;
        }
    }

    private static class ModelParamMeta {
        final int index;
        final String name;

        ModelParamMeta(int index, String name) {
            this.index = index;
            this.name = name;
        }
    }
}