package io.springperf.web.core.arg;

import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebComponentContainer;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.databinder.WebDataBinderRegistry;
import io.springperf.web.core.arg.provider.*;
import io.springperf.web.core.codec.HttpBodyCodecRegistry;
import io.springperf.web.core.mapping.MappingCacheKey;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.core.MethodParameter;
import org.springframework.util.ObjectUtils;
import org.springframework.validation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Manages a set of argument resolvers, similar to Spring's HandlerMethodArgumentResolver.
 */
@Slf4j
public class ArgumentResolverRegistry extends WebComponentContainer {

    public static final MappingCacheKey<MethodArgContext[]> MAPPING_CACHE_KEY = MappingCacheKey.createMethodCacheKey(MethodArgContext[].class);

    protected final List<StaticArgumentResolverProvider> staticArgumentResolverProviders = new ArrayList<>();

    protected WebDataBinderRegistry webDataBinderRegistry;

    protected RequestParamResolverProvider requestParamResolverProvider;

    protected ModelAttributeResolverProvider modelAttributeResolverProvider;

    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        webContext.getWebComponentWithDefault(HttpBodyCodecRegistry.class, new HttpBodyCodecRegistry());
        initStaticArgumentResolverProviders();
        webDataBinderRegistry = getWebComponentWithDefault(WebDataBinderRegistry.class, new WebDataBinderRegistry());
        requestParamResolverProvider = getWebComponent(RequestParamResolverProvider.class);
        modelAttributeResolverProvider = getWebComponent(ModelAttributeResolverProvider.class);
    }

    protected void initStaticArgumentResolverProviders() {
        registerWebComponent(new MultipartFileResolverProvider());
        registerWebComponent(new RequestBodyResolverProvider());
        registerWebComponent(new RequestHeaderResolverProvider());
        registerWebComponent(new RequestParamResolverProvider());
        registerWebComponent(new RequestPartResolverProvider());
        registerWebComponent(new PathVariableResolverProvider());
        registerWebComponent(new ModelAttributeResolverProvider());
        registerWebComponent(new HttpEntityResolverProvider());
        registerWebComponent(new ErrorsResolverProvider());
        registerWebComponent(new RequestResolverProvider());
        registerWebComponent(new ResponseResolverProvider());
        registerWebComponent(new LocaleResolverProvider());
        registerWebComponent(StaticArgumentResolverProvider.class);
        initRealComponentList(staticArgumentResolverProviders, StaticArgumentResolverProvider.class);
    }

    @Override
    public void initComponentPhase3() throws Exception {
        super.initComponentPhase3();
        if (webContext.getProps().getBoolean(PropertiesConstant.CHECK_ON_STARTUP, true)) {
            validateAllParametersResolvable();
        }
    }

    /**
     * Phase 3 validation: checks that every controller method parameter can be resolved
     * by at least one registered provider or the fallback resolver.
     * <p>This only checks {@link StaticArgumentResolverProvider#supports} — it does NOT
     * create or cache any resolver, keeping memory footprint zero for endpoints that
     * are never called.</p>
     */
    protected void validateAllParametersResolvable() {
        MappingRegistry mappingRegistry = webContext.getWebComponent(MappingRegistry.class);
        if (mappingRegistry == null) {
            return;
        }
        List<PathMappingContext> mappings = mappingRegistry.getMappingContextList();
        if (mappings.isEmpty()) {
            return;
        }
        List<String> unresolvable = new ArrayList<>();
        for (PathMappingContext mapping : mappings) {
            MethodParameter[] methodParameters = mapping.createMethodParameters();
            for (MethodParameter parameter : methodParameters) {
                if (!isParameterResolvable(parameter, mapping)) {
                    unresolvable.add(parameter.getParameterName()
                            + " (" + parameter.getNestedParameterType().getName() + ")"
                            + " in " + mapping.getUserClass().getSimpleName() + "#" + mapping.getMethod().getName());
                    continue;
                }
                validateModelAttributeConstructor(parameter, mapping, unresolvable);
            }
        }
        if (!unresolvable.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(unresolvable.size()).append(" parameter(s) have no matching resolver:")
                    .append(System.lineSeparator());
            for (String param : unresolvable) {
                sb.append("  - ").append(param).append(System.lineSeparator());
            }
            throw new IllegalStateException(sb.toString());
        }
    }

    protected boolean isParameterResolvable(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        for (StaticArgumentResolverProvider provider : staticArgumentResolverProviders) {
            if (provider.supports(parameter, mappingContext)) {
                return true;
            }
        }
        // Check fallback
        if (BeanUtils.isSimpleProperty(parameter.getNestedParameterType())) {
            return requestParamResolverProvider != null;
        }
        return modelAttributeResolverProvider != null;
    }

    /**
     * D3 fail-fast：无默认构造器的 @ModelAttribute 在首个请求才 500（resolver lazy 创建）。
     * 启动校验阶段预创建 resolver，配置错误立即暴露为启动失败。
     */
    protected void validateModelAttributeConstructor(MethodParameter parameter, MappingHandlerMethod mapping, List<String> unresolvable) {
        if (modelAttributeResolverProvider == null || !modelAttributeResolverProvider.supports(parameter, mapping)) {
            return;
        }
        try {
            modelAttributeResolverProvider.getResolver(parameter, mapping, webContext);
        } catch (IllegalStateException e) {
            unresolvable.add(parameter.getParameterName()
                    + " (" + parameter.getNestedParameterType().getName() + ")"
                    + " in " + mapping.getUserClass().getSimpleName() + "#" + mapping.getMethod().getName()
                    + ": " + e.getMessage());
        }
    }

    public Object[] resolveArguments(MappingHandlerMethod mappingContext, WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
        MethodArgContext[] methodArgContexts = getMethodArgContexts(mappingContext);
        Object[] args = new Object[methodArgContexts.length];
        for (int i = 0; i < methodArgContexts.length; i++) {
            MethodArgContext methodArgContext = methodArgContexts[i];
            if (methodArgContext.defaultArgumentResolver != null) {
                args[i] = methodArgContext.defaultArgumentResolver.resolveArgument(request, response);
                validateIfApplicable(args[i], methodArgContext, request, mappingContext);
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    protected MethodArgContext[] getMethodArgContexts(MappingHandlerMethod mappingContext) {
        MethodArgContext[] methodArgContexts = mappingContext.get(MAPPING_CACHE_KEY);
        if (methodArgContexts == null) {
            MethodParameter[] methodParameters = mappingContext.createMethodParameters();
            methodArgContexts = Arrays.stream(methodParameters).map(MethodArgContext::new).toArray(MethodArgContext[]::new);
            for (MethodArgContext methodArgContext : methodArgContexts) {
                initStaticArgResolverSupport(mappingContext, methodArgContext);
            }
            mappingContext.set(MAPPING_CACHE_KEY, methodArgContexts);
        }
        return methodArgContexts;
    }

    protected void validateIfApplicable(Object target, MethodArgContext methodArgContext, WebServerHttpRequest request, MappingHandlerMethod mappingContext) throws MethodArgumentNotValidException {
        BindingResult bindingResult = null;
        if (methodArgContext.isHasBindingResult()) {
            bindingResult = createBindingResult(target, methodArgContext, mappingContext);
            request.getRequestContext().setAttribute(methodArgContext.getBindingResultAttrKey(), bindingResult);
        }

        if (!methodArgContext.isHaveValidateAnnotation() || target == null) {
            return;
        }
        Validator validator = methodArgContext.validator;
        if (validator == null) {
            validator = getValidator(target, mappingContext);
            methodArgContext.validator = validator;
        }
        if (validator == null) {
            return;
        }
        if (bindingResult == null) {
            bindingResult = createBindingResult(target, methodArgContext, mappingContext);
        }
        Object[] validationHints = methodArgContext.getValidationHints();
        if (!ObjectUtils.isEmpty(validationHints) && validator instanceof SmartValidator) {
            ((SmartValidator) validator).validate(target, bindingResult, validationHints);
        } else {
            validator.validate(target, bindingResult);
        }

        if (bindingResult.hasErrors() && !methodArgContext.isHasBindingResult()) {
            throw new MethodArgumentNotValidException(methodArgContext.getMethodParameter(), bindingResult);
        }
    }

    protected BeanPropertyBindingResult createBindingResult(Object target, MethodArgContext methodArgContext, MappingHandlerMethod mappingContext) {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(target, methodArgContext.getParamName());
        bindingResult.initConversion(webDataBinderRegistry.getConversionService(mappingContext));
        MessageCodesResolver messageCodesResolver = webDataBinderRegistry.getMessageCodesResolver();
        if (messageCodesResolver != null) {
            bindingResult.setMessageCodesResolver(messageCodesResolver);
        }
        return bindingResult;
    }

    protected Validator getValidator(Object target, MappingHandlerMethod mappingContext) {
        List<Validator> validators = webDataBinderRegistry.getValidators(mappingContext);
        Validator validator = null;
        for (Validator v : validators) {
            if (v.supports(target.getClass())) {
                validator = v;
                break;
            }
        }
        return validator;
    }

    /**
     * 初始化静态参数解析器
     *
     * @param methodMappingContext the handler method metadata
     * @param methodArgContext     the method argument context
     */
    protected void initStaticArgResolverSupport(MappingHandlerMethod methodMappingContext, MethodArgContext methodArgContext) {
        MethodParameter parameter = methodArgContext.getMethodParameter();
        for (StaticArgumentResolverProvider provider : staticArgumentResolverProviders) {
            if (provider.supports(parameter, methodMappingContext)) {
                methodArgContext.defaultArgumentResolver = provider.getResolver(parameter, methodMappingContext, webContext);
                methodArgContext.isStaticArgResolved = true;
                break;
            }
        }
        if (methodArgContext.isStaticArgResolved) {
            return;
        }
        if (BeanUtils.isSimpleProperty(parameter.getNestedParameterType())) {
            methodArgContext.defaultArgumentResolver = requestParamResolverProvider.getResolver(parameter, methodMappingContext, webContext);
        } else {
            methodArgContext.defaultArgumentResolver = modelAttributeResolverProvider.getResolver(parameter, methodMappingContext, webContext);
        }
        methodArgContext.isStaticArgResolved = false;
    }

    public void addStaticArgumentResolverProvider(StaticArgumentResolverProvider provider) {
        registerWebComponent(provider);
        initRealComponentList(staticArgumentResolverProviders, StaticArgumentResolverProvider.class);
    }

    public WebDataBinderRegistry getWebDataBinderRegistry() {
        return webDataBinderRegistry;
    }
}
