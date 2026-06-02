package io.springperf.web.core.arg;

import lombok.Getter;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.Errors;
import org.springframework.validation.annotation.Validated;

import java.lang.annotation.Annotation;

@Getter
public class MethodArgContext {

    protected MethodParameter methodParameter;

    protected boolean isStaticArgResolved;

    protected StaticArgumentResolver defaultArgumentResolver;

    protected String paramName;

    protected boolean haveValidateAnnotation;

    protected Object[] validationHints;

    protected boolean hasBindingResult;

    protected String bindingResultAttrKey;

    public MethodArgContext(MethodParameter methodParameter) {
        this.methodParameter = methodParameter;
        paramName = getDefaultParamName(methodParameter);
        initValidateAnnotationInfo();
    }

    public static String getDefaultParamName(MethodParameter methodParameter) {
        return methodParameter.getParameterName() == null ? "Arg " + methodParameter.getParameterIndex() : methodParameter.getParameterName();
    }

    private void initValidateAnnotationInfo() {
        Annotation[] annotations = methodParameter.getParameterAnnotations();
        for (Annotation ann : annotations) {
            Validated validatedAnn = AnnotationUtils.getAnnotation(ann, Validated.class);
            if (validatedAnn != null || ann.annotationType().getSimpleName().startsWith("Valid")) {
                haveValidateAnnotation = true;
                Object hints = (validatedAnn != null ? validatedAnn.value() : AnnotationUtils.getValue(ann));
                validationHints = (hints instanceof Object[] ? (Object[]) hints : new Object[]{hints});
                break;
            }
        }
        hasBindingResult = isNextParamHasBindingResult(methodParameter);
        if (hasBindingResult) {
            int nextIndex = methodParameter.getParameterIndex() + 1;
            bindingResultAttrKey = (BindingResult.MODEL_KEY_PREFIX + nextIndex).intern();
        }
    }

    private boolean isNextParamHasBindingResult(MethodParameter parameter) {
        int i = parameter.getParameterIndex();
        Class<?>[] paramTypes = parameter.getExecutable().getParameterTypes();
        return (paramTypes.length > (i + 1) && Errors.class.isAssignableFrom(paramTypes[i + 1]));
    }
}
