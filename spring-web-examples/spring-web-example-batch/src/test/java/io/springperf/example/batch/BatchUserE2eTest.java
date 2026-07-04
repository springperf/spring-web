package io.springperf.example.batch;

import io.springperf.example.batch.model.UserBody;
import io.springperf.web.server.NettyHttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = BatchApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class BatchUserE2eTest {

    private TestRestTemplate rest;

    @Autowired
    private NettyHttpServer nettyHttpServer;

    @BeforeEach
    void setUp() {
        int actualPort = nettyHttpServer.getActualPort();
        rest = new TestRestTemplate(new RestTemplateBuilder()
                .rootUri("http://localhost:" + actualPort));
    }

    @Test
    void createUser_singleRequest_returnsBatchResult() {
        UserBody body = new UserBody();
        body.setName("testUser");
        body.setAge(30);

        ResponseEntity<String> resp = rest.postForEntity(
                "/batch/users?name=testUser", body, String.class);

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody()).startsWith("created:");
    }

    @Test
    void getUser_singleRequest_withFields() {
        ResponseEntity<String> resp = rest.getForEntity(
                "/batch/users/user-001?fields=name,email", String.class);

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody()).startsWith("user:");
        assertThat(resp.getBody()).contains("user-001");
        assertThat(resp.getBody()).contains("fields=name,email");
    }

    @Test
    void getUser_singleRequest_withoutFields() {
        ResponseEntity<String> resp = rest.getForEntity(
                "/batch/users/user-002", String.class);

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody()).startsWith("user:");
        assertThat(resp.getBody()).contains("user-002");
    }

    @Test
    void concurrentCreateUsers_allAggregatedByBatchHandler() throws Exception {
        int requestCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch latch = new CountDownLatch(requestCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            int idx = i;
            executor.submit(() -> {
                try {
                    UserBody body = new UserBody();
                    body.setName("concurrent-" + idx);
                    body.setAge(20 + idx);

                    TestRestTemplate t = new TestRestTemplate(new RestTemplateBuilder()
                            .rootUri("http://localhost:" + nettyHttpServer.getActualPort()));

                    ResponseEntity<String> resp = t.postForEntity(
                            "/batch/users?name=concurrent-" + idx, body, String.class);

                    if (resp.getStatusCodeValue() == 200
                            && resp.getBody() != null
                            && resp.getBody().startsWith("created:")) {
                        successCount.incrementAndGet();
                    } else {
                        failures.add("status=" + resp.getStatusCodeValue()
                                + ", body=" + resp.getBody());
                    }
                } catch (Exception e) {
                    failures.add("EXCEPTION:" + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(15, TimeUnit.SECONDS))
                .as("All concurrent requests should complete within timeout")
                .isTrue();
        executor.shutdown();

        assertThat(successCount.get())
                .as("All concurrent create requests should be batched and return created: prefix. Failures: " + failures)
                .isEqualTo(requestCount);
    }

    @Test
    void concurrentGetUsers_allAggregatedByBatchHandler() throws Exception {
        int requestCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch latch = new CountDownLatch(requestCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            String id = "id-" + i;
            executor.submit(() -> {
                try {
                    TestRestTemplate t = new TestRestTemplate(new RestTemplateBuilder()
                            .rootUri("http://localhost:" + nettyHttpServer.getActualPort()));

                    ResponseEntity<String> resp = t.getForEntity(
                            "/batch/users/" + id + "?fields=name", String.class);

                    if (resp.getStatusCodeValue() == 200
                            && resp.getBody() != null
                            && resp.getBody().startsWith("user:")
                            && resp.getBody().contains(id)) {
                        successCount.incrementAndGet();
                    } else {
                        failures.add("id=" + id + ", status=" + resp.getStatusCodeValue()
                                + ", body=" + resp.getBody());
                    }
                } catch (Exception e) {
                    failures.add("EXCEPTION:" + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(15, TimeUnit.SECONDS))
                .as("All concurrent get requests should complete within timeout")
                .isTrue();
        executor.shutdown();

        assertThat(successCount.get())
                .as("All concurrent get requests should be batched and return user: prefix. Failures: " + failures)
                .isEqualTo(requestCount);
    }
}