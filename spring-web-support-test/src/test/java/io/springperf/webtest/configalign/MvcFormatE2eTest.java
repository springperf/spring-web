package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code spring.mvc.format.date/time/datetime} 自定义 pattern E2E： 无 {@code @DateTimeFormat} 时使用全局默认格式；注解存在时注解优先（对齐 Boot）。
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        MvcFormatE2eTest.FormatConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.context-path=/", "spring.mvc.format.date=dd/MM/yyyy", "spring.mvc.format.time=HH:mm:ss",
                "spring.mvc.format.datetime=dd/MM/yyyy HH:mm:ss" })
class MvcFormatE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort
    int port;

    private String get(String path) throws Exception {
        okhttp3.Response resp = CLIENT
                .newCall(new okhttp3.Request.Builder().url("http://localhost:" + port + path).build()).execute();
        try {
            String body = resp.body().string();
            assertEquals(200, resp.code(), "请求 " + path + " 失败，body=" + body);
            return body.trim();
        } finally {
            resp.close();
        }
    }

    @Test
    void dateParam_usesConfiguredPattern() throws Exception {
        assertEquals("2024-01-15", get("/e2e-fmt/date?value=15/01/2024"), "spring.mvc.format.date=dd/MM/yyyy 应生效");
    }

    @Test
    void timeParam_usesConfiguredPattern() throws Exception {
        assertEquals("13:45:30", get("/e2e-fmt/time?value=13:45:30"), "spring.mvc.format.time=HH:mm:ss 应生效");
    }

    @Test
    void datetimeParam_usesConfiguredPattern() throws Exception {
        assertEquals("2024-01-15T13:45:30", get("/e2e-fmt/datetime?value=15/01/2024 13:45:30"),
                "spring.mvc.format.datetime 应生效");
    }

    @Test
    void dateTimeFormatAnnotation_overridesConfiguredPattern() throws Exception {
        assertEquals("2024-02-20", get("/e2e-fmt/date-annotation?value=2024-02-20"),
                "@DateTimeFormat(pattern=yyyy-MM-dd) 应覆盖全局配置");
    }

    @Test
    void listParam_collectsAllValues() throws Exception {
        assertEquals("[a, b, c]", get("/e2e-fmt/list?item=a&item=b&item=c"), "重复参数应收集为 List（顺序保留）");
    }

    @Test
    void chineseParam_decodedCorrectly() throws Exception {
        // UTF-8 百分号编码的 "你好"
        assertEquals("你好", get("/e2e-fmt/echo?value=%E4%BD%A0%E5%A5%BD"), "UTF-8 参数应正确解码");
    }

    @TestConfiguration
    static class FormatConfig {
        @Bean
        FormatController formatController() {
            return new FormatController();
        }
    }

    @RestController
    static class FormatController {

        @GetMapping("/e2e-fmt/date")
        public String date(@RequestParam("value") LocalDate value) {
            return value.toString();
        }

        @GetMapping("/e2e-fmt/time")
        public String time(@RequestParam("value") LocalTime value) {
            return value.toString();
        }

        @GetMapping("/e2e-fmt/datetime")
        public String datetime(@RequestParam("value") LocalDateTime value) {
            return value.toString();
        }

        @GetMapping("/e2e-fmt/date-annotation")
        public String dateAnnotation(@RequestParam("value") @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate value) {
            return value.toString();
        }

        @GetMapping("/e2e-fmt/list")
        public String list(@RequestParam("item") List<String> item) {
            return item.toString();
        }

        @GetMapping("/e2e-fmt/echo")
        public String echo(@RequestParam("value") String value) {
            return value;
        }
    }
}
