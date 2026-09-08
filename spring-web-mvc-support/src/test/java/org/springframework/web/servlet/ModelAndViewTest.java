package org.springframework.web.servlet;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.ui.ModelMap;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelAndViewTest {

    @Test
    void defaultConstructor_createsEmptyModelAndView() {
        ModelAndView mav = new ModelAndView();
        assertNull(mav.getViewName());
        assertNull(mav.getView());
        assertFalse(mav.hasView());
        assertFalse(mav.wasCleared());
    }

    @Test
    void viewNameConstructor_setsViewName() {
        ModelAndView mav = new ModelAndView("testView");
        assertEquals("testView", mav.getViewName());
        assertTrue(mav.hasView());
        assertTrue(mav.isReference());
    }

    @Test
    void viewConstructor_setsView() {
        View view = (model, request, response) -> {};
        ModelAndView mav = new ModelAndView(view);
        assertSame(view, mav.getView());
        assertTrue(mav.hasView());
        assertFalse(mav.isReference());
    }

    @Test
    void viewNameAndModelConstructor_setsBoth() {
        Map<String, Object> model = Collections.singletonMap("key", "value");
        ModelAndView mav = new ModelAndView("testView", model);
        assertEquals("testView", mav.getViewName());
        assertEquals("value", mav.getModel().get("key"));
    }

    @Test
    void viewNameAndStatusConstructor_setsStatus() {
        ModelAndView mav = new ModelAndView("testView", HttpStatus.NOT_FOUND);
        assertEquals(HttpStatus.NOT_FOUND, mav.getStatus());
    }

    @Test
    void addObject_addsToModel() {
        ModelAndView mav = new ModelAndView("testView");
        mav.addObject("key", "value");
        assertEquals("value", mav.getModel().get("key"));
    }

    @Test
    void addObject_withoutName_generatesName() {
        ModelAndView mav = new ModelAndView("testView");
        mav.addObject("value");
        assertFalse(mav.getModel().isEmpty());
    }

    @Test
    void clear_resetsState() {
        ModelAndView mav = new ModelAndView("testView", "key", "value");
        assertTrue(mav.hasView());
        mav.clear();
        assertFalse(mav.hasView());
        assertTrue(mav.wasCleared());
        assertTrue(mav.isEmpty());
    }

    @Test
    void isEmpty_noViewNoModel_returnsTrue() {
        ModelAndView mav = new ModelAndView();
        assertTrue(mav.isEmpty());
    }

    @Test
    void isEmpty_withView_returnsFalse() {
        ModelAndView mav = new ModelAndView("testView");
        assertFalse(mav.isEmpty());
    }

    @Test
    void getModelMap_createsModelIfNull() {
        ModelAndView mav = new ModelAndView();
        assertNotNull(mav.getModelMap());
        assertInstanceOf(ModelMap.class, mav.getModelMap());
    }

    @Test
    void toString_containsViewInfo() {
        ModelAndView mav = new ModelAndView("testView");
        assertTrue(mav.toString().contains("testView"));
    }
}