package io.springperf.webtest;

import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ViewRenderTest extends BaseE2ETest {

    @Test
    void helloView_shouldRenderThymeleafTemplate() throws Exception {
        Request req = new Request.Builder()
                .url("http://localhost:9090/api/view/hello?name=TestUser")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("Hello TestUser!"),
                    "Response should contain rendered message, got: " + body);
            assertTrue(body.contains("<h1>"),
                    "Response should be HTML, got: " + body);
            String contentType = resp.header("Content-Type");
            assertNotNull(contentType, "Content-Type header should be present");
            assertTrue(contentType.contains("text/html"),
                    "Content-Type should be text/html, got: " + contentType);
        }
    }

    @Test
    void helloView_defaultName() throws Exception {
        Request req = new Request.Builder()
                .url("http://localhost:9090/api/view/hello")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("Hello World!"),
                    "Response should contain default name, got: " + body);
        }
    }

    @Test
    void redirectView_shouldReturn302() throws Exception {
        okhttp3.OkHttpClient noRedirectClient = CLIENT.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        Request req = new Request.Builder()
                .url("http://localhost:9090/api/view/redirect")
                .get()
                .build();
        try (Response resp = noRedirectClient.newCall(req).execute()) {
            assertEquals(302, resp.code());
            String location = resp.header("Location");
            assertNotNull(location, "Location header should be present");
            assertTrue(location.contains("hello?name=redirected"),
                    "Location should contain redirected URL, got: " + location);
        }
    }

    @Test
    void modelAndView_shouldRenderThymeleafTemplate() throws Exception {
        Request req = new Request.Builder()
                .url("http://localhost:9090/api/view/mav?name=MVTest")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("Hello from ModelAndView MVTest!"),
                    "Response should contain ModelAndView message, got: " + body);
        }
    }

    @Test
    void modelAttribute_shouldMergeIntoModel() throws Exception {
        Request req = new Request.Builder()
                .url("http://localhost:9090/api/view/model-attr?name=Alice&age=30")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("bound Alice/30"),
                    "Response should contain bound @ModelAttribute data, got: " + body);
        }
    }

    @Test
    void controllerAdviceModelAttribute_shouldPopulateModel() throws Exception {
        Request req = new Request.Builder()
                .url("http://localhost:9090/api/view/advice")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("app=SpringPerfWeb, title=Perf View Test"),
                    "Response should contain @ControllerAdvice @ModelAttribute data, got: " + body);
        }
    }

    @Test
    void beetlView_shouldRenderBeetlTemplate() throws Exception {
        Request req = new Request.Builder()
                .url("http://localhost:9090/api/view/hello-beetl?name=BeetlUser")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("Hello from Beetl BeetlUser!"),
                    "Response should contain Beetl rendered message, got: " + body);
        }
    }

    @Test
    void freemarkerView_shouldRenderFreemarkerTemplate() throws Exception {
        Request req = new Request.Builder()
                .url("http://localhost:9090/api/view/hello-ftl?name=FtlUser")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("Hello from FreeMarker FtlUser!"),
                    "Response should contain FreeMarker rendered message, got: " + body);
        }
    }

    @Test
    void pathVariable_shouldInjectIntoModel() throws Exception {
        Request req = new Request.Builder()
                .url("http://localhost:9090/api/view/hello-path/PathUser")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("Path User: <span>PathUser</span>"),
                    "Response should contain path variable injected into model, got: " + body);
        }
    }

    @Test
    void bindingResult_shouldInjectIntoModel() throws Exception {
        Request req = new Request.Builder()
                .url("http://localhost:9090/api/view/binding")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("HasErrors: <span>true</span>"),
                    "BindingResult should be auto-merged into model, got: " + body);
        }
    }

    @Test
    void localModelAttribute_shouldPopulateModel() throws Exception {
        Request req = new Request.Builder()
                .url("http://localhost:9090/api/view/local")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("localKey=localValue"),
                    "Local @ModelAttribute method should populate model, got: " + body);
        }
    }

    @Test
    void exceptionHandler_shouldReturnViewName() throws Exception {
        Request req = new Request.Builder()
                .url("http://localhost:9090/api/view/boom")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("handled: boom"),
                    "Exception handler should render error view, got: " + body);
        }
    }
}