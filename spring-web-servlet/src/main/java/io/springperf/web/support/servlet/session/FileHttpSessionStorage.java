package io.springperf.web.support.servlet.session;

import org.springframework.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * 基于文件的会话存储（对齐 Spring Boot {@code server.servlet.session.persistent} /
 * {@code store-dir}）：每 session 一个文件，JDK 序列化，重启可恢复未过期会话。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><b>每 session 一文件</b>：{@code <store-dir>/<sessionId>.session}，便于并发写与局部恢复。</li>
 *   <li><b>原子写</b>：先写 {@code <id>.tmp}，再 {@code ATOMIC_MOVE} 替换目标，避免半写损坏。</li>
 *   <li><b>不可序列化属性跳过</b>：逐属性序列化，失败者跳过并 warn，不影响其余属性与整个会话。</li>
 *   <li><b>坏文件容错</b>：启动加载时反序列化失败（损坏 / 类版本不匹配）跳过并 warn，不影响启动。</li>
 *   <li><b>内存优先</b>：运行时读写走内存 map，落盘仅用于重启恢复。</li>
 * </ul>
 */
public class FileHttpSessionStorage implements HttpSessionStorage {

    private static final Logger log = LoggerFactory.getLogger(FileHttpSessionStorage.class);

    private static final long CLEANUP_INTERVAL_MS = 60_000L;
    private static final String FILE_SUFFIX = ".session";
    private static final String TMP_SUFFIX = ".tmp";

    private static final SecureRandom SESSION_ID_RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final Path storeDir;
    private final Set<String> excludeAttributes;
    private final ConcurrentMap<String, HttpSessionData> sessions = new ConcurrentHashMap<>();
    private final Thread cleanupThread;

    public FileHttpSessionStorage(Path storeDir) {
        this(storeDir, Collections.emptySet());
    }

    public FileHttpSessionStorage(Path storeDir, Set<String> excludeAttributes) {
        this.storeDir = storeDir;
        this.excludeAttributes = excludeAttributes != null ? excludeAttributes : Collections.emptySet();
        try {
            Files.createDirectories(storeDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create session store-dir: " + storeDir, e);
        }
        loadExistingSessions();
        this.cleanupThread = new Thread(this::cleanupLoop, "session-file-cleanup");
        this.cleanupThread.setDaemon(true);
        this.cleanupThread.start();
    }

    /** 启动加载：扫描目录中所有 {@code *.session} 文件，反序列化未过期会话到内存。 */
    private void loadExistingSessions() {
        long now = System.currentTimeMillis();
        try (Stream<Path> files = Files.list(storeDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(FILE_SUFFIX)).forEach(path -> {
                HttpSessionData data = readSessionFile(path);
                if (data == null) {
                    return;
                }
                if (data.isExpired(now) || data.isInvalid()) {
                    // 已过期/已失效：不加载，删除文件
                    deleteFileQuietly(path);
                    return;
                }
                sessions.put(data.getId(), data);
            });
        } catch (IOException e) {
            log.warn("Failed to scan session store-dir {}, starting empty", storeDir, e);
        }
        if (!sessions.isEmpty()) {
            log.info("Restored {} persisted session(s) from {}", sessions.size(), storeDir);
        }
    }

    /** 读取单个会话文件；损坏或不可反序列化时返回 null 并删除坏文件（容错）。 */
    @Nullable
    private HttpSessionData readSessionFile(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes));
            Object obj = ois.readObject();
            if (obj instanceof HttpSessionData) {
                return (HttpSessionData) obj;
            }
            log.warn("Unexpected session file content (not HttpSessionData): {}", path);
        } catch (Exception e) {
            log.warn("Failed to deserialize session file {} (corrupted or incompatible), discarded: {}",
                    path, e.toString());
        }
        deleteFileQuietly(path);
        return null;
    }

    @Override
    @Nullable
    public HttpSessionData getSession(String sessionId) {
        HttpSessionData session = sessions.get(sessionId);
        if (session != null && session.isExpired(System.currentTimeMillis())) {
            sessions.remove(sessionId, session);
            deleteFileQuietly(fileOf(sessionId));
            return null;
        }
        return session;
    }

    @Override
    public HttpSessionData createSession() {
        String id = generateSessionId();
        HttpSessionData session = new HttpSessionData(id, System.currentTimeMillis());
        sessions.put(id, session);
        return session;
    }

    /**
     * 落盘会话：过滤排除名单与不可序列化属性后，原子写入 {@code <id>.session}。
     * 写入失败只 warn，不影响请求处理。
     */
    @Override
    public void saveSession(HttpSessionData session) {
        if (session == null || session.isInvalid()) {
            return;
        }
        sessions.put(session.getId(), session);
        byte[] payload = serialize(session);
        if (payload == null) {
            return;
        }
        Path target = fileOf(session.getId());
        Path tmp = storeDir.resolve(session.getId() + TMP_SUFFIX);
        try {
            Files.write(tmp, payload);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Failed to persist session {} to {}: {}", session.getId(), target, e.toString());
            deleteFileQuietly(tmp);
        }
    }

    /**
     * 序列化会话：排除名单命中或不可序列化的属性被跳过（warn），其余正常写入。
     * 整体序列化异常时返回 null（放弃本次落盘）。
     */
    @Nullable
    private byte[] serialize(HttpSessionData session) {
        // 构造可序列化的副本：跳过排除名单与不可序列化属性
        HttpSessionData copy = new HttpSessionData(session.getId(), session.getCreationTime());
        copy.setLastAccessedTime(session.getLastAccessedTime());
        copy.setMaxInactiveInterval(session.getMaxInactiveInterval());
        for (Map.Entry<String, Object> entry : session.getAttributes().entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (excludeAttributes.contains(name)) {
                continue;
            }
            if (value != null && !(value instanceof Serializable)) {
                log.warn("Session attribute '{}' is not serializable ({}), skipped from persistence",
                        name, value.getClass().getName());
                continue;
            }
            if (value instanceof Serializable && !isActuallySerializable((Serializable) value)) {
                log.warn("Session attribute '{}' ({}) failed serialization check, skipped from persistence",
                        name, value.getClass().getName());
                continue;
            }
            copy.setAttribute(name, value);
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
            try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
                oos.writeObject(copy);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            log.warn("Failed to serialize session {}: {}", session.getId(), e.toString());
            return null;
        }
    }

    /** 试序列化单个属性，判定其是否真正可序列化（含其引用图）。 */
    private static boolean isActuallySerializable(Serializable value) {
        try (ObjectOutputStream oos = new ObjectOutputStream(new ByteArrayOutputStream(64))) {
            oos.writeObject(value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
        deleteFileQuietly(fileOf(sessionId));
    }

    /** 当前内存中未过期的会话数。 */
    public int getActiveSessionCount() {
        long now = System.currentTimeMillis();
        return (int) sessions.values().stream().filter(s -> !s.isExpired(now)).count();
    }

    public Path getStoreDir() {
        return storeDir;
    }

    private Path fileOf(String sessionId) {
        return storeDir.resolve(sessionId + FILE_SUFFIX);
    }

    private void deleteFileQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("Failed to delete session file {}: {}", path, e.toString());
        }
    }

    private static String generateSessionId() {
        byte[] bytes = new byte[32];
        SESSION_ID_RANDOM.nextBytes(bytes);
        char[] hex = new char[bytes.length * 2];
        for (int i = 0, j = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            hex[j++] = HEX[b >>> 4];
            hex[j++] = HEX[b & 0x0F];
        }
        return new String(hex);
    }

    private void cleanupLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(CLEANUP_INTERVAL_MS);
                long now = System.currentTimeMillis();
                sessions.values().removeIf(s -> {
                    if (s.isExpired(now)) {
                        deleteFileQuietly(fileOf(s.getId()));
                        return true;
                    }
                    return false;
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    public void shutdown() {
        if (cleanupThread != null) {
            cleanupThread.interrupt();
        }
    }

    /** 从逗号分隔的排除名单字符串解析为集合（空白项忽略）。 */
    public static Set<String> parseExcludeList(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (String item : raw.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** 供测试：当前内存中的会话快照（id -> data）。 */
    Map<String, HttpSessionData> snapshot() {
        return new HashMap<>(sessions);
    }
}
