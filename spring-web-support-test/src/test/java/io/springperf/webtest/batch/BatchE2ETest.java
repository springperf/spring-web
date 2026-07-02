package io.springperf.webtest.batch;

import io.springperf.webtest.BaseE2ETest;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BatchE2ETest extends BaseE2ETest {

    private static final String BASE_URL = "http://localhost:9090/api/batch/echo?msg=hello";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    @Test
    void batchRequests_areProcessedByBatchHandler() throws Exception {
        int requestCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch latch = new CountDownLatch(requestCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            executor.submit(() -> {
                try {
                    Request req = new Request.Builder()
                            .url(BASE_URL)
                            .get()
                            .build();
                    try (Response resp = CLIENT.newCall(req).execute()) {
                        String body = resp.body() != null ? resp.body().string() : "";
                        if (resp.code() == 200
                                && body.startsWith("batched:")
                                && body.contains("msg=hello")) {
                            successCount.incrementAndGet();
                        } else {
                            failures.add(resp.code() + ":" + body);
                        }
                    }
                } catch (Exception e) {
                    failures.add("EXCEPTION:" + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Requests did not complete within timeout");
        executor.shutdown();

        assertEquals(requestCount, successCount.get(),
                "All requests should return 200 with msg=hello injected. Failures: " + failures);
    }

    @Test
    void singleRequest_alsoProcessedByBatchHandler() throws Exception {
        Request req = new Request.Builder()
                .url(BASE_URL)
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.startsWith("batched:"),
                    "Response should come from batch handler: " + body);
            assertTrue(body.contains("msg=hello"),
                    "Response should contain injected msg=hello: " + body);
        }
    }

    @Test
    void postWithMultiParams_injectsBodyQueryAndPathVar() throws Exception {
        String json = "{\"name\":\"testUser\",\"age\":25}";
        Request req = new Request.Builder()
                .url("http://localhost:9090/api/batch/echo/testPath?msg=helloMulti")
                .post(RequestBody.create(JSON, json))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.startsWith("batched:"), "Should be batched: " + body);
            assertTrue(body.contains("msg=helloMulti"), "Missing msg injection: " + body);
            assertTrue(body.contains("name=testUser"), "Missing body.name injection: " + body);
            assertTrue(body.contains("age=25"), "Missing body.age injection: " + body);
            assertTrue(body.contains("pathVal=testPath"), "Missing pathVal injection: " + body);
        }
    }

    @Test
    void maxBatchSize_limitsBatchSizeInResponses() throws Exception {
        int requestCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch latch = new CountDownLatch(requestCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            executor.submit(() -> {
                try {
                    Request req = new Request.Builder()
                            .url("http://localhost:9090/api/batch/echo-batchsize?msg=test")
                            .get()
                            .build();
                    try (Response resp = CLIENT.newCall(req).execute()) {
                        String body = resp.body() != null ? resp.body().string() : "";
                        if (resp.code() == 200
                                && body.contains(":maxBatchSize=2")) {
                            successCount.incrementAndGet();
                        } else {
                            failures.add(resp.code() + ":" + body);
                        }
                    }
                } catch (Exception e) {
                    failures.add("EXCEPTION:" + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Requests did not complete within timeout");
        executor.shutdown();

        assertEquals(requestCount, successCount.get(),
                "All maxBatchSize requests should succeed. Failures: " + failures);
    }

    @Test
    void exceptionInBatchHandler_propagatesToAllRequests() throws Exception {
        int requestCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch latch = new CountDownLatch(requestCount);
        AtomicInteger errorCount = new AtomicInteger(0);
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            executor.submit(() -> {
                try {
                    Request req = new Request.Builder()
                            .url("http://localhost:9090/api/batch/echo-error?msg=test")
                            .get()
                            .build();
                    try (Response resp = CLIENT.newCall(req).execute()) {
                        String body = resp.body() != null ? resp.body().string() : "";
                        if (resp.isSuccessful()) {
                            // Successful response — check body for wrapped error
                            if (body.contains("\"code\":500")) {
                                errorCount.incrementAndGet();
                            } else {
                                failures.add(resp.code() + ":" + body);
                            }
                        } else {
                            // Non-2xx status code also indicates error propagation
                            errorCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    failures.add("EXCEPTION:" + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Requests did not complete within timeout");
        executor.shutdown();

        assertEquals(requestCount, errorCount.get(),
                "All requests should receive error response. Failures: " + failures);
    }

    @Test
    void maxConsumers_processesAllRequestsInWorkerPool() throws Exception {
        int requestCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch latch = new CountDownLatch(requestCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            executor.submit(() -> {
                try {
                    Request req = new Request.Builder()
                            .url("http://localhost:9090/api/batch/echo-consumers?msg=test")
                            .get()
                            .build();
                    try (Response resp = CLIENT.newCall(req).execute()) {
                        String body = resp.body() != null ? resp.body().string() : "";
                        if (resp.code() == 200
                                && body.startsWith("consumed:")
                                && body.contains(":consumerSize=2")) {
                            successCount.incrementAndGet();
                        } else {
                            failures.add(resp.code() + ":" + body);
                        }
                    }
                } catch (Exception e) {
                    failures.add("EXCEPTION:" + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Requests did not complete within timeout");
        executor.shutdown();

        assertEquals(requestCount, successCount.get(),
                "All maxConsumers requests should succeed. Failures: " + failures);
    }
}
