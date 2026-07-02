package io.springperf.example.upload.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
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
        Files.createDirectories(target.getParent());
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
        return Files.list(root)
                .filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .collect(Collectors.toList());
    }

    public boolean delete(String filename) throws IOException {
        Path file = root.resolve(filename).normalize();
        if (!file.startsWith(root)) {
            throw new SecurityException("invalid path: " + filename);
        }
        return Files.deleteIfExists(file);
    }
}