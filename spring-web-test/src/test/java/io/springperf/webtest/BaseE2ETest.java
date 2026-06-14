package io.springperf.webtest;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

@SpringBootTest(classes = TestApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BaseE2ETest {

    public static final OkHttpClient CLIENT;

    static {
        HttpLoggingInterceptor logging =
                new HttpLoggingInterceptor(message -> {
                    System.out.println("[OKHTTP] " + message);
                });
        logging.setLevel(HttpLoggingInterceptor.Level.HEADERS);
        CLIENT = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(10))
                .writeTimeout(Duration.ofSeconds(10))
                .retryOnConnectionFailure(true)
                .addInterceptor(logging)
                .build();
    }
}
