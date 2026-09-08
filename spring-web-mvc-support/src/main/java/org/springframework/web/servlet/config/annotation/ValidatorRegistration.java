package org.springframework.web.servlet.config.annotation;

import org.springframework.validation.Validator;

/**
 * Shim of Spring MVC's {@code ValidatorRegistration}.
 * Collects a custom {@link Validator} from {@link WebMvcConfigurer#configureValidator(ValidatorRegistration)}
 * and bridges it to the framework's native {@code WebDataBinderRegistry}.
 */
public class ValidatorRegistration {

    private Validator validator;

    /**
     * Set the custom validator to use for model data validation.
     */
    public ValidatorRegistration validator(Validator validator) {
        this.validator = validator;
        return this;
    }

    public Validator getValidator() {
        return this.validator;
    }
}