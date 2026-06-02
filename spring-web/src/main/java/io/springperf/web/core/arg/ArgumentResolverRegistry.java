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
import org.springframework.beans.BeanUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.util.ObjectUtils;
import org.springframework.validation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Manages a set of argument resolvers, similar to Spring's HandlerMethodArgumentResolver.
 */
public class ArgumentResolverRegistry extends WebComponentContainer {

    public static final MappingCacheKey<MethodArgContext[]> MAPPING_CACHE_KEY = MappingCacheKey.createMethodCacheKey(MethodArgContext[].class);

    protected final List<StaticArgumentResolverProvider> staticArgumentResolverProviders = new ArrayList<>();

    protected final List<RuntimeArgumentResolver> runtimeArgumentResolvers = new ArrayList<>();

    protected WebDataBinderRegistry webDataBinderRegistry;

    protected RequestParamResolverProvider requestParamResolverProvider;

    protected ModelAttributeResolverProvider modelAttributeResolverProvider;

    protected ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        webContext.getWebComponentWithDefault(HttpBodyCodecRegistry.class, new HttpBodyCodecRegistry());
        initStaticArgumentResolverProviders();
        initRuntimeArgumentResolvers();
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

    protected void initRuntimeArgumentResolvers() {
        registerWebComponent(RuntimeArgumentResolver.class);
        initRealComponentList(runtimeArgumentResolvers, RuntimeArgumentResolver.class);
    }

    @Override
    public void initComponentPhase3() throws Exception {
        super.initComponentPhase3();
        MappingRegistry mappingRegistry = webContext.getWebComponent(MappingRegistry.class);
        if (mappingRegistry == null) {
            return;
        }
        boolean check = webContext.getProps().getBoolean(PropertiesConstant.CHECK_ON_STARTUP, true);
        for (PathMappingContext mappingContext : mappingRegistry.getMappingContextList()) {
            if (mappingContext.isOptimize() || check) {
                getMethodArgContexts(mappingContext, mappingContext.isOptimize());
            }
        }
    }

    public Object[] resolveArguments(MappingHandlerMethod mappingContext, WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
        MethodArgContext[] methodArgContexts = getMethodArgContexts(mappingContext, true);
        Object[] args = new Object[methodArgContexts.length];
        for (int i = 0; i < methodArgContexts.length; i++) {
            MethodArgContext methodArgContext = methodArgContexts[i];
            boolean handled = false;
            if (methodArgContext.isStaticArgResolved) {
                args[i] = methodArgContext.defaultArgumentResolver.resolveArgument(request, response);
                handled = true;
            } else {
                for (RuntimeArgumentResolver r : runtimeArgumentResolvers) {
                    MethodParameter mp = methodArgContext.getMethodParameter();
                    if (r.supportsParameter(mp, request, response)) {
                        args[i] = r.resolveArgument(mp, request, response);
                        handled = true;
                        break;
                    }
                }
                if (!handled && methodArgContext.defaultArgumentResolver != null) {
                    args[i] = methodArgContext.defaultArgumentResolver.resolveArgument(request, response);
                    handled = true;
                }
            }
            if (handled) {
                validateIfApplicable(args[i], methodArgContext, request, mappingContext);
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    protected MethodArgContext[] getMethodArgContexts(MappingHandlerMethod mappingContext, boolean cache) {
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
        Validator validator = getValidator(target, mappingContext);
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
     * @param methodMappingContext
     * @param methodArgContext
     */
    protected void initStaticArgResolverSupport(MappingHandlerMethod methodMappingContext, MethodArgContext methodArgContext) {
        MethodParameter parameter = methodArgContext.getMethodParameter();
        parameter.initParameterNameDiscovery(parameterNameDiscoverer);
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

    public void addRuntimeArgumentResolver(RuntimeArgumentResolver argumentResolver) {
        registerWebComponent(argumentResolver);
        initRealComponentList(runtimeArgumentResolvers, RuntimeArgumentResolver.class);
    }

    public void addStaticArgumentResolverProvider(StaticArgumentResolverProvider provider) {
        registerWebComponent(provider);
        initRealComponentList(staticArgumentResolverProviders, StaticArgumentResolverProvider.class);
    }
}
