package io.springperf.webtest.configalign;

import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

/**
 * message-codes E2E 的共享装置： 用自定义 {@link Validator} 制造字段错误（无需 jakarta.validation 实现）， 断言目标则是 {@code BindingResult} 上被
 * {@code MessageCodesResolver} 解析出的错误码集合。
 */
final class MessageCodesSupport {

    private MessageCodesSupport() {
    }

    /** 请求体表单：仅一个字段，校验目标。 */
    public static class CodeForm {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /** 恒对 {@code name} 字段报 {@code NotBlank} 错误的校验器（supports 限定本表单）。 */
    public static class RejectingNameValidator implements Validator {

        @Override
        public boolean supports(Class<?> clazz) {
            return CodeForm.class.isAssignableFrom(clazz);
        }

        @Override
        public void validate(Object target, Errors errors) {
            // 显式 default message：错误体渲染取 FieldError.getDefaultMessage()，
            // 无 MessageSource 时不给默认值会得到 null，断言无从下手。
            errors.rejectValue("name", "NotBlank", "must-not-be-blank");
        }
    }
}
