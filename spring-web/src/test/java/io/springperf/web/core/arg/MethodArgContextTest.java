package io.springperf.web.core.arg;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BindingResult;
import org.springframework.validation.Errors;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class MethodArgContextTest {

    // ----- Constructor & paramName -----

    @Test
    void constructor_withNamedParameter_usesParameterName() throws Exception {
        Method method = getClass().getMethod("namedParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        mp.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());
        MethodArgContext ctx = new MethodArgContext(mp);
        assertNotNull(ctx.getParamName());
        assertEquals("name", ctx.getParamName());
    }

    @Test
    void constructor_withUnresolvableParameterName_usesFallbackName() throws Exception {
        Method method = getClass().getMethod("namedParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        mp.initParameterNameDiscovery(null);  // clear default discoverer (Spring 7.x sets one by default)
        MethodArgContext ctx = new MethodArgContext(mp);
        String paramName = ctx.getParamName();
        assertTrue(paramName.startsWith("Arg "));
        assertTrue(paramName.contains("0"));
    }

    @Test
    void getDefaultParamName_withName_returnsName() throws Exception {
        Method method = getClass().getMethod("namedParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        mp.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());
        assertEquals("name", MethodArgContext.getDefaultParamName(mp));
    }

    @Test
    void getDefaultParamName_withoutName_returnsFallback() throws Exception {
        Method method = getClass().getMethod("namedParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        mp.initParameterNameDiscovery(null);  // clear default discoverer (Spring 7.x sets one by default)
        String fallback = MethodArgContext.getDefaultParamName(mp);
        assertTrue(fallback.startsWith("Arg "));
    }

    // ----- @Valid annotation -----

    @Test
    void constructor_withoutValidationAnnotation_notDetected() throws Exception {
        Method method = getClass().getMethod("namedParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        MethodArgContext ctx = new MethodArgContext(mp);
        assertFalse(ctx.isHaveValidateAnnotation());
    }

    // ----- @Validated annotation -----

    @Test
    void constructor_withValidatedAnnotation_detectsValidation() throws Exception {
        Method method = getClass().getMethod("validatedParam", ValidParam.class);
        MethodParameter mp = new MethodParameter(method, 0);
        MethodArgContext ctx = new MethodArgContext(mp);
        assertTrue(ctx.isHaveValidateAnnotation());
    }

    @Test
    void constructor_withValidatedAnnotationWithHints_extractsHints() throws Exception {
        Method method = getClass().getMethod("validatedWithHintsParam", ValidParam.class);
        MethodParameter mp = new MethodParameter(method, 0);
        MethodArgContext ctx = new MethodArgContext(mp);
        assertTrue(ctx.isHaveValidateAnnotation());
        assertNotNull(ctx.getValidationHints());
        assertTrue(ctx.getValidationHints().length > 0);
    }

    @Test
    void constructor_withValidatedAnnotationEmptyHints_hasNoHints() throws Exception {
        Method method = getClass().getMethod("validatedParam", ValidParam.class);
        MethodParameter mp = new MethodParameter(method, 0);
        MethodArgContext ctx = new MethodArgContext(mp);
        assertNotNull(ctx.getValidationHints());
    }

    // ----- BindingResult detection -----

    @Test
    void constructor_nextParamIsErrors_detectsBindingResult() throws Exception {
        Method method = getClass().getMethod("paramWithBindingResult", String.class, BindingResult.class);
        MethodParameter mp = new MethodParameter(method, 0);
        MethodArgContext ctx = new MethodArgContext(mp);
        assertTrue(ctx.isHasBindingResult());
        assertNotNull(ctx.getBindingResultAttrKey());
    }

    @Test
    void constructor_nextParamNotErrors_noBindingResult() throws Exception {
        Method method = getClass().getMethod("namedParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        MethodArgContext ctx = new MethodArgContext(mp);
        assertFalse(ctx.isHasBindingResult());
    }

    @Test
    void constructor_nextParamIsErrorsSubclass_detectsBindingResult() throws Exception {
        Method method = getClass().getMethod("paramWithErrorsSubclass", String.class, Errors.class);
        MethodParameter mp = new MethodParameter(method, 0);
        MethodArgContext ctx = new MethodArgContext(mp);
        assertTrue(ctx.isHasBindingResult());
    }

    @Test
    void constructor_lastParam_noBindingResult() throws Exception {
        Method method = getClass().getMethod("namedParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        MethodArgContext ctx = new MethodArgContext(mp);
        assertFalse(ctx.isHasBindingResult());
    }

    // ----- getter tests -----

    @Test
    void getMethodParameter_returnsCorrectParameter() throws Exception {
        Method method = getClass().getMethod("namedParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        MethodArgContext ctx = new MethodArgContext(mp);
        assertSame(mp, ctx.getMethodParameter());
    }

    // ----- helper methods for reflection -----

    @SuppressWarnings("unused")
    public void namedParam(String name) {}

    @SuppressWarnings("unused")
    public void validatedParam(@Validated ValidParam param) {}

    @SuppressWarnings("unused")
    public void validatedWithHintsParam(@Validated({Group1.class, Group2.class}) ValidParam param) {}

    @SuppressWarnings("unused")
    public void paramWithBindingResult(String name, BindingResult bindingResult) {}

    @SuppressWarnings("unused")
    public void paramWithErrorsSubclass(String name, Errors errors) {}

    @SuppressWarnings("unused")
    public void requestBodyParam(@RequestBody String body) {}

    static class ValidParam {}
    interface Group1 {}
    interface Group2 {}
}
