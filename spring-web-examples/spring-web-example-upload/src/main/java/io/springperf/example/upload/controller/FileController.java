package io.springperf.example.upload.controller;

import io.springperf.example.upload.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/files")
public class FileController {

    private final FileStorageService storage;

    public FileController(FileStorageService storage) {
        this.storage = storage;
    }

    /**
     * 单文件上传 curl -F "file=@/path/to/file.txt" http://localhost:8083/files/upload
     */
    @PostMapping("/upload")
    public Map<String, String> upload(@RequestParam("file") MultipartFile file) throws IOException {
        storage.store(file.getOriginalFilename(), file.getBytes());
        log.info("uploaded: {}", file.getOriginalFilename());
        Map<String, String> result = new HashMap<>();
        result.put("filename", file.getOriginalFilename());
        result.put("size", String.valueOf(file.getSize()));
        return result;
    }

    /**
     * 多文件上传 curl -F "files=@/path/to/a.txt" -F "files=@/path/to/b.txt" http://localhost:8083/files/upload-multi
     */
    @PostMapping("/upload-multi")
    public List<String> uploadMulti(@RequestParam("files") List<MultipartFile> files) throws IOException {
        for (MultipartFile file : files) {
            storage.store(file.getOriginalFilename(), file.getBytes());
            log.info("uploaded: {}", file.getOriginalFilename());
        }
        return files.stream().map(MultipartFile::getOriginalFilename).collect(Collectors.toList());
    }

    /**
     * 文件下载 curl -O http://localhost:8083/files/download?filename=test.txt
     */
    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam String filename) {
        Resource resource = storage.loadAsResource(filename);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachmentHeader(filename)).body(resource);
    }

    /**
     * 组装 {@code Content-Disposition} 头。文件名来自请求参数，**不能**直接拼进响应头：能破坏头结构的 字符（CR/LF/引号/反斜杠）先替换掉，非 ASCII 名再用 RFC 5987 的
     * {@code filename*} 传递， {@code filename=} 只留 ASCII 回退（老客户端用）。
     */
    private static String attachmentHeader(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "attachment";
        }
        String ascii = filename.replaceAll("[\\r\\n\"\\\\]", "_").replaceAll("[^\\x20-\\x7E]", "_");
        StringBuilder header = new StringBuilder("attachment; filename=\"").append(ascii)
                .append("\"; filename*=UTF-8''");
        for (byte b : filename.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-'
                    || c == '_' || c == '~') {
                header.append((char) c);
            } else {
                header.append('%').append("0123456789ABCDEF".charAt(c >> 4)).append("0123456789ABCDEF".charAt(c & 0xF));
            }
        }
        return header.toString();
    }

    /**
     * 文件列表 curl http://localhost:8083/files
     */
    @GetMapping
    public List<String> list() throws IOException {
        return storage.listFiles();
    }

    /**
     * 删除文件 curl -X DELETE http://localhost:8083/files?filename=test.txt
     */
    @DeleteMapping
    public Map<String, String> delete(@RequestParam String filename) throws IOException {
        boolean deleted = storage.delete(filename);
        Map<String, String> result = new HashMap<>();
        result.put("filename", filename);
        result.put("deleted", String.valueOf(deleted));
        return result;
    }
}
