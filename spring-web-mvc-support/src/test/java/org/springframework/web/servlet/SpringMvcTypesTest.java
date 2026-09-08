package org.springframework.web.servlet;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class SpringMvcTypesTest {

    // ---- NoHandlerFoundException ----

    @Test
    void noHandlerFoundException_constructorStoresFields() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");

        NoHandlerFoundException ex = new NoHandlerFoundException("GET", "/api/test", headers);

        assertEquals("GET", ex.getHttpMethod());
        assertEquals("/api/test", ex.getRequestURL());
        assertSame(headers, ex.getHeaders());
    }

    @Test
    void noHandlerFoundException_messageContainsHttpMethodAndUrl() {
        NoHandlerFoundException ex = new NoHandlerFoundException("POST", "/users", new HttpHeaders());

        String msg = ex.getMessage();
        assertTrue(msg.contains("POST"));
        assertTrue(msg.contains("/users"));
    }

    @Test
    void noHandlerFoundException_extendsServletException() {
        NoHandlerFoundException ex = new NoHandlerFoundException("GET", "/", new HttpHeaders());

        assertInstanceOf(javax.servlet.ServletException.class, ex);
    }

    @Test
    void noHandlerFoundException_acceptsNullHeaders() {
        NoHandlerFoundException ex = new NoHandlerFoundException("GET", "/test", null);

        assertNull(ex.getHeaders());
    }

    @Test
    void noHandlerFoundException_acceptsEmptyHeaders() {
        NoHandlerFoundException ex = new NoHandlerFoundException("GET", "/test", new HttpHeaders());

        assertNotNull(ex.getHeaders());
        assertTrue(ex.getHeaders().isEmpty());
    }

    // ---- ModelAndView ----

    @Test
    void modelAndView_defaultConstructor_fieldsAreNull() {
        ModelAndView mav = new ModelAndView();

        assertNull(mav.getView());
        assertNull(mav.getViewName());
        assertNull(mav.getStatus());
        assertFalse(mav.hasView());
        assertFalse(mav.isReference());
        assertFalse(mav.wasCleared());
        assertTrue(mav.isEmpty());
    }

    @Test
    void modelAndView_withViewName() {
        ModelAndView mav = new ModelAndView("testView");

        assertEquals("testView", mav.getViewName());
        assertNull(mav.getView());
        assertTrue(mav.hasView());
        assertTrue(mav.isReference());
    }

    @Test
    void modelAndView_withViewObject() {
        View view = (model, request, response) -> {};
        ModelAndView mav = new ModelAndView(view);

        assertSame(view, mav.getView());
        assertNull(mav.getViewName());
        assertTrue(mav.hasView());
        assertFalse(mav.isReference());
    }

    @Test
    void modelAndView_withViewNameAndModel() {
        ModelAndView mav = new ModelAndView("test", Collections.singletonMap("key", "value"));

        assertEquals("test", mav.getViewName());
        assertEquals(1, mav.getModel().size());
        assertEquals("value", mav.getModel().get("key"));
    }

    @Test
    void modelAndView_withViewNameAndHttpStatus() {
        ModelAndView mav = new ModelAndView("error", HttpStatus.NOT_FOUND);

        assertEquals("error", mav.getViewName());
        assertEquals(HttpStatus.NOT_FOUND, mav.getStatus());
    }

    @Test
    void modelAndView_withViewNameModelAndHttpStatus() {
        ModelAndView mav = new ModelAndView("error", Collections.singletonMap("err", "msg"), HttpStatus.INTERNAL_SERVER_ERROR);

        assertEquals("error", mav.getViewName());
        assertEquals("msg", mav.getModel().get("err"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, mav.getStatus());
    }

    @Test
    void modelAndView_withViewNameAndModelObject() {
        ModelAndView mav = new ModelAndView("test", "objName", "objValue");

        assertEquals("test", mav.getViewName());
        assertEquals("objValue", mav.getModel().get("objName"));
    }

    @Test
    void modelAndView_withViewObjectAndModelObject() {
        View view = (model, request, response) -> {};
        ModelAndView mav = new ModelAndView(view, "objName", "objValue");

        assertSame(view, mav.getView());
        assertEquals("objValue", mav.getModel().get("objName"));
    }

    @Test
    void modelAndView_setViewName_overridesView() {
        ModelAndView mav = new ModelAndView("original");
        mav.setViewName("updated");

        assertEquals("updated", mav.getViewName());
    }

    @Test
    void modelAndView_setView_overridesViewName() {
        View view = (model, request, response) -> {};
        ModelAndView mav = new ModelAndView("name");
        mav.setView(view);

        assertSame(view, mav.getView());
        assertNull(mav.getViewName());
    }

    @Test
    void modelAndView_addObject() {
        ModelAndView mav = new ModelAndView("test");
        mav.addObject("key", "val");

        assertEquals("val", mav.getModel().get("key"));
    }

    @Test
    void modelAndView_addAllObjects() {
        ModelAndView mav = new ModelAndView("test");
        mav.addAllObjects(Collections.singletonMap("a", "1"));

        assertEquals("1", mav.getModel().get("a"));
    }

    @Test
    void modelAndView_clear_resetsState() {
        ModelAndView mav = new ModelAndView("test", Collections.singletonMap("k", "v"));
        mav.clear();

        assertNull(mav.getViewName());
        assertTrue(mav.isEmpty());
        assertTrue(mav.wasCleared());
    }

    @Test
    void modelAndView_clearAfterNotEmpty_wasClearedReturnsFalse() {
        ModelAndView mav = new ModelAndView("test");
        mav.clear();
        mav.addObject("newKey", "newVal");

        assertFalse(mav.wasCleared());
    }

    @Test
    void modelAndView_setStatus() {
        ModelAndView mav = new ModelAndView("test");
        mav.setStatus(HttpStatus.OK);

        assertEquals(HttpStatus.OK, mav.getStatus());
    }

    @Test
    void modelAndView_getModelMap_createsOnDemand() {
        ModelAndView mav = new ModelAndView("test");

        assertNotNull(mav.getModelMap());
        mav.getModelMap().addAttribute("key", "value");
        assertEquals("value", mav.getModel().get("key"));
    }
}
