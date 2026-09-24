package io.springperf.web.util;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.util.MultiValueMap;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.*;

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
            Class<?> valueType = ResolvableType.forMethodParameter(methodParam).as(MultiValueMap.class).getGeneric(1)
                    .resolve();
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
            // 用 getParameterAnnotation 的返回值直接判空（该 API 本身 @Nullable），替代
            // hasParameterAnnotation + getParameterAnnotation 的成对写法：两者语义等价，
            // 但后者会多一次反射查找（本方法是参数解析热路径），且静态分析无法关联那句守卫，
            // 会产生 NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE 误报（本类的 10 条即来源于此）。
            if (RequestParam.class == annotation) {
                RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
                if (requestParam != null) {
                    name = ObjectUtils.isEmpty(requestParam.value()) ? requestParam.name() : requestParam.value();
                }
            } else if (RequestHeader.class == annotation) {
                RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
                if (requestHeader != null) {
                    name = ObjectUtils.isEmpty(requestHeader.value()) ? requestHeader.name() : requestHeader.value();
                }
            } else if (PathVariable.class == annotation) {
                PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
                if (pathVariable != null) {
                    name = ObjectUtils.isEmpty(pathVariable.value()) ? pathVariable.name() : pathVariable.value();
                }
            } else if (ModelAttribute.class == annotation) {
                ModelAttribute modelAttribute = parameter.getParameterAnnotation(ModelAttribute.class);
                if (modelAttribute != null) {
                    name = ObjectUtils.isEmpty(modelAttribute.value()) ? modelAttribute.name() : modelAttribute.value();
                }
            } else if (RequestPart.class == annotation) {
                RequestPart requestPart = parameter.getParameterAnnotation(RequestPart.class);
                if (requestPart != null) {
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
            // 同 getParameterName：直接判 getParameterAnnotation 的返回值，语义等价且少一次反射查找
            if (RequestParam.class == annotation) {
                RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
                if (requestParam != null) {
                    required = requestParam.required();
                }
            } else if (RequestHeader.class == annotation) {
                RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
                if (requestHeader != null) {
                    required = requestHeader.required();
                }
            } else if (RequestPart.class == annotation) {
                RequestPart requestPart = parameter.getParameterAnnotation(RequestPart.class);
                if (requestPart != null) {
                    required = requestPart.required();
                }
            }
        }
        return required;
    }

    public static String getDefaultValue(MethodParameter parameter, Class<? extends Annotation>... supportClass) {
        String defaultValue = null;
        for (Class<? extends Annotation> annotation : supportClass) {
            // 同 getParameterName：直接判 getParameterAnnotation 的返回值
            if (RequestParam.class == annotation) {
                RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
                if (requestParam != null) {
                    defaultValue = requestParam.defaultValue();
                }
            } else if (RequestHeader.class == annotation) {
                RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
                if (requestHeader != null) {
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
