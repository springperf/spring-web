package io.springperf.web.util;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetaUtilsTest {

    // ----- getCollectionParameterType -----

    @Test
    void getCollectionParameterType_list_returnsGenericType() throws Exception {
        Method method = MetaUtilsTest.class.getMethod("listParam", List.class);
        MethodParameter mp = new MethodParameter(method, 0);
        assertEquals(String.class, MetaUtils.getCollectionParameterType(mp));
    }

    @Test
    void getCollectionParameterType_nonCollection_returnsNull() throws Exception {
        Method method = MetaUtilsTest.class.getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        assertNull(MetaUtils.getCollectionParameterType(mp));
    }

    @Test
    void getCollectionParameterType_unresolvableGeneric_returnsNull() throws Exception {
        Method method = MetaUtilsTest.class.getMethod("rawListParam", List.class);
        MethodParameter mp = new MethodParameter(method, 0);
        // raw List without generic should return null
        assertNull(MetaUtils.getCollectionParameterType(mp));
    }

    // ----- getMapParameterType -----

    @Test
    void getMapParameterType_map_returnsValueType() throws Exception {
        Method method = MetaUtilsTest.class.getMethod("mapParam", Map.class);
        MethodParameter mp = new MethodParameter(method, 0);
        assertEquals(Integer.class, MetaUtils.getMapParameterType(mp));
    }

    @Test
    void getMapParameterType_nonMap_returnsNull() throws Exception {
        Method method = MetaUtilsTest.class.getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        assertNull(MetaUtils.getMapParameterType(mp));
    }

    // ----- getParameterName -----

    @Test
    void getParameterName_requestParamWithValue() throws Exception {
        Method method = MetaUtilsTest.class.getMethod("annotatedParam", String.class, String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        // First parameter has @RequestParam("userId"), should return "userId"
        // Note: we need to actually init the parameter name discovery
        mp.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());
        String name = MetaUtils.getParameterName(mp, RequestParam.class);
        assertEquals("userId", name);
    }

    @Test
    void getParameterName_requestParamWithoutValue() throws Exception {
        Method method = MetaUtilsTest.class.getMethod("annotatedParam", String.class, String.class);
        MethodParameter mp = new MethodParameter(method, 1);
        mp.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());
        // Second parameter has @RequestParam without explicit name, should fall back to param name
        String name = MetaUtils.getParameterName(mp, RequestParam.class);
        assertNotNull(name);
    }

    @Test
    void getParameterName_pathVariable() throws Exception {
        Method method = MetaUtilsTest.class.getMethod("pathVarParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        assertEquals("id", MetaUtils.getParameterName(mp, PathVariable.class));
    }

    @Test
    void getParameterName_pathVariableNameAttribute() throws Exception {
        Method method = MetaUtilsTest.class.getMethod("pathVarNameParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        assertEquals("userId", MetaUtils.getParameterName(mp, PathVariable.class));
    }

    @Test
    void getParameterName_noMatchingAnnotation_returnsParameterName() throws Exception {
        Method method = MetaUtilsTest.class.getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        mp.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());
        String name = MetaUtils.getParameterName(mp, RequestParam.class);
        assertNotNull(name);
    }

    @Test
    void getParameterName_requestHeader() throws Exception {
        Method method = MetaUtilsTest.class.getMethod("headerParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        assertEquals("X-Token", MetaUtils.getParameterName(mp, RequestHeader.class));
    }

    // ----- getDefaultValue -----

    @Test
    void getDefaultValue_requestParamWithDefault() throws Exception {
        Method method = MetaUtilsTest.class.getMethod("defaultValueParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        String defaultValue = MetaUtils.getDefaultValue(mp, RequestParam.class);
        assertEquals("10", defaultValue);
    }

    @Test
    void getDefaultValue_noDefault_returnsNull() throws Exception {
        Method method = MetaUtilsTest.class.getMethod("annotatedParam", String.class, String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        String defaultValue = MetaUtils.getDefaultValue(mp, RequestParam.class);
        // == reference comparison can fail on annotation proxies, check equals
        assertTrue(defaultValue == null || ValueConstants.DEFAULT_NONE.equals(defaultValue));
    }

    @Test
    void getDefaultValue_requestHeaderWithDefault() throws Exception {
        Method method = MetaUtilsTest.class.getMethod("headerDefaultParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        String defaultValue = MetaUtils.getDefaultValue(mp, RequestHeader.class);
        assertEquals("default-val", defaultValue);
    }

    // ----- getRequired -----

    @Test
    void getRequired_requestParamDefaultTrue() throws Exception {
        Method method = MetaUtilsTest.class.getMethod("annotatedParam", String.class, String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        assertTrue(MetaUtils.getRequired(mp, RequestParam.class));
    }

    @Test
    void getRequired_requestParamExplicitFalse() throws Exception {
        Method method = MetaUtilsTest.class.getMethod("notRequiredParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        assertFalse(MetaUtils.getRequired(mp, RequestParam.class));
    }

    // ----- helper methods for reflection -----

    public void listParam(List<String> list) {}
    @SuppressWarnings("rawtypes")
    public void rawListParam(List list) {}
    public void mapParam(Map<String, Integer> map) {}
    public void stringParam(String s) {}
    public void annotatedParam(@RequestParam("userId") String userId, @RequestParam String name) {}
    public void pathVarParam(@PathVariable("id") String id) {}
    public void pathVarNameParam(@PathVariable(name = "userId") String id) {}
    public void headerParam(@RequestHeader("X-Token") String token) {}
    public void defaultValueParam(@RequestParam(defaultValue = "10") String page) {}
    public void headerDefaultParam(@RequestHeader(defaultValue = "default-val") String header) {}
    public void notRequiredParam(@RequestParam(required = false) String optional) {}
}
