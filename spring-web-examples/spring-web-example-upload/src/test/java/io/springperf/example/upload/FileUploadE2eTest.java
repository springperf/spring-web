package io.springperf.example.upload;

import io.springperf.web.server.NettyHttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = UploadApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class FileUploadE2eTest {

    private static Path tempUploadDir;

    @DynamicPropertySource
    static void configureUploadDir(DynamicPropertyRegistry registry) throws IOException {
        tempUploadDir = Files.createTempDirectory("perf-upload-test-");
        registry.add("upload.dir", tempUploadDir::toString);
    }

    @AfterAll
    static void cleanUp() throws IOException {
        if (tempUploadDir != null && Files.exists(tempUploadDir)) {
            try (Stream<Path> paths = Files.walk(tempUploadDir)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        }
    }

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
    void uploadSingleFile_andDownload() throws IOException {
        String filename = "test-single.txt";
        String content = "hello single upload";

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content.getBytes()) {
            @Override
            public String getFilename() {
                return filename;
            }
        });

        ResponseEntity<Map> uploadResp = rest.postForEntity("/files/upload", body, Map.class);
        assertThat(uploadResp.getStatusCodeValue()).isEqualTo(200);
        assertThat(uploadResp.getBody()).isNotNull();
        assertThat(uploadResp.getBody().get("filename")).isEqualTo(filename);

        ResponseEntity<byte[]> downloadResp = rest.exchange(
                "/files/download?filename=" + filename, HttpMethod.GET, null, byte[].class);
        assertThat(downloadResp.getStatusCodeValue()).isEqualTo(200);

        String diskContent = new String(Files.readAllBytes(tempUploadDir.resolve(filename)), StandardCharsets.UTF_8);
        assertThat(diskContent).isEqualTo(content);
    }

    @Test
    void uploadMultiFiles() throws IOException {
        String filename1 = "multi-a.txt";
        String filename2 = "multi-b.txt";

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("files", new ByteArrayResource("content a".getBytes()) {
            @Override
            public String getFilename() {
                return filename1;
            }
        });
        body.add("files", new ByteArrayResource("content b".getBytes()) {
            @Override
            public String getFilename() {
                return filename2;
            }
        });

        ResponseEntity<List> resp = rest.postForEntity("/files/upload-multi", body, List.class);
        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody()).containsExactly(filename1, filename2);

        String disk1 = new String(Files.readAllBytes(tempUploadDir.resolve(filename1)), StandardCharsets.UTF_8);
        assertThat(disk1).isEqualTo("content a");

        String disk2 = new String(Files.readAllBytes(tempUploadDir.resolve(filename2)), StandardCharsets.UTF_8);
        assertThat(disk2).isEqualTo("content b");
    }

    @Test
    void downloadNonExistentFile_returns404() {
        ResponseEntity<byte[]> resp = rest.exchange(
                "/files/download?filename=nonexist-file.txt", HttpMethod.GET, null, byte[].class);

        assertThat(resp.getStatusCodeValue()).isEqualTo(404);
    }

    @Test
    void fileList_containsUploadedFiles() {
        String filename = "list-test.txt";

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource("list content".getBytes()) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        rest.postForEntity("/files/upload", body, Map.class);

        ResponseEntity<List> listResp = rest.getForEntity("/files", List.class);
        assertThat(listResp.getStatusCodeValue()).isEqualTo(200);
        assertThat(listResp.getBody()).isNotNull();
        assertThat(listResp.getBody()).contains(filename);
    }

    @Test
    void deleteFile_andVerify() {
        String filename = "delete-test.txt";

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource("to be deleted".getBytes()) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        rest.postForEntity("/files/upload", body, Map.class);

        ResponseEntity<Map> deleteResp = rest.exchange(
                "/files?filename=" + filename, HttpMethod.DELETE, null, Map.class);
        assertThat(deleteResp.getStatusCodeValue()).isEqualTo(200);
        assertThat(deleteResp.getBody()).isNotNull();
        assertThat(deleteResp.getBody().get("filename")).isEqualTo(filename);
        assertThat(deleteResp.getBody().get("deleted")).isEqualTo("true");

        assertThat(Files.exists(tempUploadDir.resolve(filename))).isFalse();
    }

    @Test
    void deleteNonExistentFile_returnsDeletedFalse() {
        ResponseEntity<Map> resp = rest.exchange(
                "/files?filename=nonexist-delete.txt", HttpMethod.DELETE, null, Map.class);

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("filename")).isEqualTo("nonexist-delete.txt");
        assertThat(resp.getBody().get("deleted")).isEqualTo("false");
    }
}