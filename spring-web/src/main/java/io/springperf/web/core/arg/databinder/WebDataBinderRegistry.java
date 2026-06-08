package io.springperf.web.core.arg.databinder;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.WebComponentWrapperUtils;
import io.springperf.web.core.mapping.MappingCacheKey;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.convert.ConversionService;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.util.ReflectionUtils;
import org.springframework.validation.MessageCodesResolver;
import org.springframework.validation.Validator;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.support.WebBindingInitializer;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.method.support.InvocableHandlerMethod;

import java.lang.reflect.Method;
import java.util.*;

@Slf4j
public class WebDataBinderRegistry extends BaseWebComponent {

    public static final ReflectionUtils.MethodFilter INIT_BINDER_METHODS = method -> AnnotatedElementUtils.hasAnnotation(method, InitBinder.class);

    private static final MappingCacheKey<ConversionService> CONVERSION_SERVICE_KEY = MappingCacheKey.createClassCacheKey(ConversionService.class);
    private static final MappingCacheKey<List<Validator>> VALIDATOR_KEY = (MappingCacheKey) MappingCacheKey.createClassCacheKey(List.class);
    private static final MappingCacheKey<WebDataBinderFactory> BINDER_FACTORY_KEY = MappingCacheKey.createClassCacheKey(WebDataBinderFactory.class);
    protected WebDataBinderFactory webDataBinderFactory;
    protected WebBindingInitializer webBindingInitializer;
    protected Map<ControllerAdviceBean, Set<Method>> initBinderAdviceCache = new LinkedHashMap<>();

    private ConversionService defaultConversionService;

    private Validator defaultValidator;

    private MessageCodesResolver messageCodesResolver;

    @Override
    public void initComponentPhase1() throws Exception {
        super.initComponentPhase1();
        webDataBinderFactory = webContext.getBeanFromCtx(WebDataBinderFactory.class);
        webBindingInitializer = webContext.getBeanFromCtx(WebBindingInitializer.class);
        List<ControllerAdviceBean> adviceBeans = ControllerAdviceBean.findAnnotatedBeans(webContext.getCtx());
        for (ControllerAdviceBean adviceBean : adviceBeans) {
            Set<Method> binderMethods = MethodIntrospector.selectMethods(adviceBean.getBeanType(), INIT_BINDER_METHODS);
            if (!binderMethods.isEmpty()) {
                this.initBinderAdviceCache.put(adviceBean, binderMethods);
            }
        }
        defaultConversionService = WebComponentWrapperUtils.getComponentWithDefault(webContext, ConversionService.class, new DefaultFormattingConversionService());
        if (defaultValidator == null) {
            defaultValidator = webContext.getBeanFromCtx(Validator.class);
        }
        messageCodesResolver = webContext.getBeanFromCtx(MessageCodesResolver.class);
    }


    public ConversionService getConversionService(MappingHandlerMethod mappingContext) {
        ConversionService conversionService = mappingContext.get(CONVERSION_SERVICE_KEY);
        if (conversionService != null) {
            return conversionService;
        }
        try {
            WebDataBinderFactory webDataBinderFactory = getWebDataBinderFactory(mappingContext);
            WebDataBinder dataBinder = webDataBinderFactory.createBinder(null, null, "");
            conversionService = dataBinder.getConversionService();
        } catch (Exception e) {
            log.warn("getConversionService error", e);
        }
        if (conversionService == null) {
            conversionService = defaultConversionService;
        }
        mappingContext.set(CONVERSION_SERVICE_KEY, conversionService);
        return conversionService;
    }

    public List<Validator> getValidators(MappingHandlerMethod mappingContext) {
        List<Validator> validators = mappingContext.get(VALIDATOR_KEY);
        if (validators != null) {
            return validators;
        }
        validators = new ArrayList<>();
        try {
            WebDataBinderFactory webDataBinderFactory = getWebDataBinderFactory(mappingContext);
            WebDataBinder dataBinder = webDataBinderFactory.createBinder(null, null, "");
            validators.addAll(dataBinder.getValidators());
        } catch (Exception e) {
            log.warn("getValidators error", e);
        }
        if (defaultValidator != null) {
            validators.add(defaultValidator);
        }
        mappingContext.set(VALIDATOR_KEY, validators);
        return validators;
    }

    public WebDataBinderFactory getWebDataBinderFactory(MappingHandlerMethod mappingContext) {
        if (webDataBinderFactory != null) {
            return webDataBinderFactory;
        }
        WebDataBinderFactory webDataBinderFactory = mappingContext.get(BINDER_FACTORY_KEY);
        if (webDataBinderFactory != null) {
            return webDataBinderFactory;
        }
        List<InvocableHandlerMethod> initBinderMethods = getInitBinderMethods(mappingContext);
        webDataBinderFactory = new PerfDataBinderFactory(initBinderMethods, webBindingInitializer);
        mappingContext.set(BINDER_FACTORY_KEY, webDataBinderFactory);
        return webDataBinderFactory;
    }

    protected List<InvocableHandlerMethod> getInitBinderMethods(MappingHandlerMethod handlerMethod) {
        Class<?> handlerType = handlerMethod.getBeanType();
        List<InvocableHandlerMethod> initBinderMethods = new ArrayList<>();
        for (Map.Entry<ControllerAdviceBean, Set<Method>> entry : initBinderAdviceCache.entrySet()) {
            ControllerAdviceBean controllerAdviceBean = entry.getKey();
            Set<Method> methodSet = entry.getValue();
            if (controllerAdviceBean.isApplicableToBeanType(handlerType)) {
                Object bean = controllerAdviceBean.resolveBean();
                for (Method method : methodSet) {
                    initBinderMethods.add(createInitBinderMethod(bean, method));
                }
            }
        }
        Set<Method> methods = MethodIntrospector.selectMethods(handlerType, INIT_BINDER_METHODS);
        for (Method method : methods) {
            Object bean = handlerMethod.getBean();
            initBinderMethods.add(createInitBinderMethod(bean, method));
        }
        return initBinderMethods;
    }

    protected InvocableHandlerMethod createInitBinderMethod(Object bean, Method method) {
        InvocableHandlerMethod binderMethod = new InvocableHandlerMethod(bean, method);
        binderMethod.setDataBinderFactory(webDataBinderFactory);
        return binderMethod;
    }

    public MessageCodesResolver getMessageCodesResolver() {
        return messageCodesResolver;
    }

    /**
     * Set a default validator to use for model data validation.
     * Overrides any validator discovered from the Spring context.
     *
     * @param validator the validator to set
     */
    public void setDefaultValidator(Validator validator) {
        this.defaultValidator = validator;
    }
}
