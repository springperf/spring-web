package io.springperf.webtest.bridge;

import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

public class CustomE2eValidator implements Validator {

    @Override
    public boolean supports(Class<?> clazz) {
        return ValidatedDto.class == clazz;
    }

    @Override
    public void validate(Object target, Errors errors) {
        ValidatedDto dto = (ValidatedDto) target;
        if ("fail".equals(dto.getName())) {
            errors.rejectValue("name", "custom.error", "custom-validator-rejected");
        }
    }
}