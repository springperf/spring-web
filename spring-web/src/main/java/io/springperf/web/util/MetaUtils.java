package io.springperf.web.util;

import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.util.MultiValueMap;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.*;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class MetaUtils {

    public static Class<?> getCollectionParameterType(MethodParameter methodParam) {
        Class<?> paramType = methodParam.getNestedParameterType();
        if (Collection.class == paramType || List.class.isAssignableFrom(paramType)) {
            Class<?> valueType = ResolvableType.forMethodParameter(methodParam).asCollection().resolveGeneric();
            if (valueType != null) {
                return valueType;
            }
        }
        return null;
    }

    public static Class<?> getMapParameterType(MethodParameter methodParam) {
        Class<?> paramType = methodParam.getNestedParameterType();
        if (MultiValueMap.class.isAssignableFrom(paramType)) {
            Class<?> valueType = ResolvableType.forMethodParameter(methodParam).as(MultiValueMap.class).getGeneric(1).resolve();
            if (valueType != null) {
                return valueType;
            }
        } else if (Map.class.isAssignableFrom(paramType)) {
            Class<?> valueType = ResolvableType.forMethodParameter(methodParam).asMap().getGeneric(1).resolve();
            if (valueType != null) {
                return valueType;
            }
        }
        return null;
    }

    public static String getParameterName(MethodParameter parameter, Class<? extends Annotation>... supportClass) {
        String name = null;
        for (Class<? extends Annotation> annotation : supportClass) {
            if (RequestParam.class == annotation) {
                if (parameter.hasParameterAnnotation(RequestParam.class)) {
                    RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
                    name = ObjectUtils.isEmpty(requestParam.value()) ? requestParam.name() : requestParam.value();
                }
            } else if (RequestHeader.class == annotation) {
                if (parameter.hasParameterAnnotation(RequestHeader.class)) {
                    RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
                    name = ObjectUtils.isEmpty(requestHeader.value()) ? requestHeader.name() : requestHeader.value();
                }
            } else if (PathVariable.class == annotation) {
                if (parameter.hasParameterAnnotation(PathVariable.class)) {
                    PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
                    name = ObjectUtils.isEmpty(pathVariable.value()) ? pathVariable.name() : pathVariable.value();
                }
            } else if (ModelAttribute.class == annotation) {
                if (parameter.hasParameterAnnotation(ModelAttribute.class)) {
                    ModelAttribute modelAttribute = parameter.getParameterAnnotation(ModelAttribute.class);
                    name = ObjectUtils.isEmpty(modelAttribute.value()) ? modelAttribute.name() : modelAttribute.value();
                }
            } else if (RequestPart.class == annotation) {
                if (parameter.hasParameterAnnotation(RequestPart.class)) {
                    RequestPart requestPart = parameter.getParameterAnnotation(RequestPart.class);
                    name = ObjectUtils.isEmpty(requestPart.value()) ? requestPart.name() : requestPart.value();
                }
            }
        }
        if (ObjectUtils.isEmpty(name)) {
            name = parameter.getParameterName();
        }
        return name;
    }

    public static boolean getRequired(MethodParameter parameter, Class<? extends Annotation>... supportClass) {
        boolean required = false;
        for (Class<? extends Annotation> annotation : supportClass) {
            if (RequestParam.class == annotation) {
                if (parameter.hasParameterAnnotation(RequestParam.class)) {
                    RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
                    required = requestParam.required();
                }
            } else if (RequestHeader.class == annotation) {
                if (parameter.hasParameterAnnotation(RequestHeader.class)) {
                    RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
                    required = requestHeader.required();
                }
            } else if (RequestPart.class == annotation) {
                if (parameter.hasParameterAnnotation(RequestPart.class)) {
                    RequestPart requestPart = parameter.getParameterAnnotation(RequestPart.class);
                    required = requestPart.required();
                }
            }
        }
        return required;
    }

    public static String getDefaultValue(MethodParameter parameter, Class<? extends Annotation>... supportClass) {
        String defaultValue = null;
        for (Class<? extends Annotation> annotation : supportClass) {
            if (RequestParam.class == annotation) {
                if (parameter.hasParameterAnnotation(RequestParam.class)) {
                    RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
                    defaultValue = requestParam.defaultValue();
                }
            } else if (RequestHeader.class == annotation) {
                if (parameter.hasParameterAnnotation(RequestHeader.class)) {
                    RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
                    defaultValue = requestHeader.defaultValue();
                }
            }
        }
        if (ValueConstants.DEFAULT_NONE.equals(defaultValue)) {
            defaultValue = null;
        }
        return defaultValue;
    }
}
