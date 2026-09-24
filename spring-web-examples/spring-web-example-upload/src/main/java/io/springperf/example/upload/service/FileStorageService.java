package io.springperf.example.upload.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FileStorageService {

    @Value("${upload.dir:./uploaded-files}")
    private String uploadDir;

    private Path root;

    @PostConstruct
    public void init() throws IOException {
        root = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(root);
        log.info("upload directory: {}", root);
    }

    public void store(String filename, byte[] content) throws IOException {
        Path target = root.resolve(filename).normalize();
        if (!target.startsWith(root)) {
            throw new SecurityException("invalid path: " + filename);
        }
        // Path.getParent() 声明为 @Nullable：无父目录（纯文件名）时无需创建
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(target, content);
    }

    public Resource loadAsResource(String filename) {
        Path file = root.resolve(filename).normalize();
        if (!file.startsWith(root)) {
            throw new SecurityException("invalid path: " + filename);
        }
        return new FileSystemResource(file.toFile());
    }

    public List<String> listFiles() throws IOException {
        // try-with-resources：Files.list 的流持有目录句柄，不关闭会泄漏（原先直接 return 漏掉了）
        try (java.util.stream.Stream<Path> files = Files.list(root)) {
            // Path.getFileName() 声明为 @Nullable：无文件名时退化为完整路径
            return files.filter(Files::isRegularFile).map(p -> {
                Path name = p.getFileName();
                return name != null ? name.toString() : p.toString();
            }).collect(Collectors.toList());
        }
    }

    public boolean delete(String filename) throws IOException {
        Path file = root.resolve(filename).normalize();
        if (!file.startsWith(root)) {
            throw new SecurityException("invalid path: " + filename);
        }
        return Files.deleteIfExists(file);
    }
}
