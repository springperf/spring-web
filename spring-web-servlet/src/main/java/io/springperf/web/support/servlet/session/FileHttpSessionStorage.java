package io.springperf.web.support.servlet.session;

import org.springframework.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import io.springperf.web.context.PropertiesConstant;

/**
 * 基于文件的会话存储（对齐 Spring Boot {@code server.servlet.session.persistent} / {@code store-dir}）：每 session 一个文件，JDK
 * 序列化，重启可恢复未过期会话。
 * <p>
 * 设计要点：
 * </p>
 * <ul>
 * <li><b>每 session 一文件</b>：{@code <store-dir>/<sessionId>.session}，便于并发写与局部恢复。</li>
 * <li><b>原子写</b>：先写 {@code <id>.tmp}，再 {@code ATOMIC_MOVE} 替换目标，避免半写损坏。</li>
 * <li><b>不可序列化属性跳过</b>：逐属性序列化，失败者跳过并 warn，不影响其余属性与整个会话。</li>
 * <li><b>坏文件容错</b>：启动加载时反序列化失败（损坏 / 类版本不匹配）跳过并 warn，不影响启动。</li>
 * <li><b>可选反序列化过滤</b>：构造时可传入 {@link ObjectInputFilter}（来自 {@code
 * server.servlet.session.persistent-deserialization-filter}），只放行规格内的类；不传 = 不过滤。</li>
 * <li><b>内存优先</b>：运行时读写走内存 map，落盘仅用于重启恢复。</li>
 * </ul>
 */
public class FileHttpSessionStorage implements HttpSessionStorage {

    private static final Logger log = LoggerFactory.getLogger(FileHttpSessionStorage.class);

    private static final long CLEANUP_INTERVAL_MS = 60_000L;
    private static final String FILE_SUFFIX = ".session";
    private static final String TMP_SUFFIX = ".tmp";
    /** 启动清扫 {@code *.tmp} 的年龄阈值：避免误删**另一个实例**正在写的 tmp。 */
    private static final long STALE_TMP_AGE_MS = 3_600_000L;

    private static final SecureRandom SESSION_ID_RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final Path storeDir;
    /** {@code storeDir} 的规范化绝对路径：仅用于 {@link #fileOf} 的越界判定（保持 {@link #getStoreDir()} 原值不变）。 */
    private final Path storeDirNormalized;
    private final Set<String> excludeAttributes;
    /** 可选的类过滤器；null = 不过滤（默认，与历史行为一致）。 */
    private final ObjectInputFilter deserializationFilter;
    private final ConcurrentMap<String, HttpSessionData> sessions = new ConcurrentHashMap<>();
    /**
     * 会话写锁（分条带）。
     * <p>
     * 落盘是「先写 {@code <id>.tmp} 再 ATOMIC_MOVE」两步，同一会话并发 save 会交错写同一个 tmp 名， 出现相互截断/半写；{@code saveSession} 与
     * {@code removeSession}（{@code invalidate} 的落点）并发时， 删除之后的那次 move 还会把已失效会话写回磁盘 —— 重启后被恢复，即「会话复活」。二者都要靠互斥消除。
     * </p>
     * <p>
     * 用<b>固定条带</b>而非「每会话一把锁的 map」：后者必须在写完后移除条目以免随会话数无界增长， 而移除与 {@code computeIfAbsent}
     * 之间存在窗口（持有旧锁的等待者拿到的锁已不在表里，新来者另建一把） ——互斥就此失效。条带数量恒定（与本类生命周期一致），没有移除，也没有这个窗口；代价只是哈希冲突的不同会话偶尔串行一次一次磁盘写。
     * </p>
     */
    private static final int WRITE_LOCK_STRIPES = 64;
    private final ReentrantLock[] writeLocks = new ReentrantLock[WRITE_LOCK_STRIPES];
    private final Thread cleanupThread;

    public FileHttpSessionStorage(Path storeDir) {
        this(storeDir, Collections.emptySet());
    }

    public FileHttpSessionStorage(Path storeDir, Set<String> excludeAttributes) {
        this(storeDir, excludeAttributes, null);
    }

    public FileHttpSessionStorage(Path storeDir, Set<String> excludeAttributes,
            @Nullable ObjectInputFilter deserializationFilter) {
        this.storeDir = storeDir;
        this.storeDirNormalized = storeDir.toAbsolutePath().normalize();
        this.excludeAttributes = excludeAttributes != null ? excludeAttributes : Collections.emptySet();
        this.deserializationFilter = deserializationFilter;
        for (int i = 0; i < WRITE_LOCK_STRIPES; i++) {
            writeLocks[i] = new ReentrantLock();
        }
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

    /**
     * 启动清扫遗留的 {@code *.tmp}，只删「足够旧」的（见 {@link #STALE_TMP_AGE_MS}）。
     * <p>
     * {@code <id>.tmp → <id>.session} 的 {@code ATOMIC_MOVE} 是两步的，进程在两步之间被杀就会留下 tmp；而启动扫描只认
     * {@code *.session}，这些文件原先会永久累积。年龄阈值是为了不误删另一个实例正在写的 tmp。
     * </p>
     */
    private void sweepStaleTmpFiles() {
        long cutoff = System.currentTimeMillis() - STALE_TMP_AGE_MS;
        try (Stream<Path> files = Files.list(storeDir)) {
            files.filter(p -> {
                Path name = p.getFileName();
                return name != null && name.toString().endsWith(TMP_SUFFIX);
            }).forEach(path -> {
                try {
                    if (Files.getLastModifiedTime(path).toMillis() < cutoff) {
                        log.info("Removing stale session temp file {}", path);
                        deleteFileQuietly(path);
                    }
                } catch (IOException e) {
                    log.debug("Failed to inspect session temp file {}", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("Failed to sweep session temp files in {}", storeDir, e);
        }
    }

    /** 启动加载：清扫遗留 tmp，再扫描目录中所有 {@code *.session} 文件，反序列化未过期会话到内存。 */
    private void loadExistingSessions() {
        sweepStaleTmpFiles();
        long now = System.currentTimeMillis();
        try (Stream<Path> files = Files.list(storeDir)) {
            // Path.getFileName() 声明为 @Nullable：无文件名的条目直接跳过
            files.filter(p -> {
                Path name = p.getFileName();
                return name != null && name.toString().endsWith(FILE_SUFFIX);
            }).forEach(path -> {
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
        AtomicReference<String> rejectedClass = new AtomicReference<>();
        try {
            byte[] bytes = Files.readAllBytes(path);
            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                if (deserializationFilter != null) {
                    ois.setObjectInputFilter(recordingRejections(deserializationFilter, rejectedClass));
                }
                Object obj = ois.readObject();
                if (obj instanceof HttpSessionData) {
                    return (HttpSessionData) obj;
                }
            }
            log.warn("Unexpected session file content (not HttpSessionData): {}", path);
        } catch (Exception e) {
            String rejected = rejectedClass.get();
            if (rejected != null) {
                // 与「文件损坏」分开上报：前者要改配置、后者是磁盘或类版本问题，运维动作完全不同。
                // 混成一行「corrupted or incompatible」时，用户调白名单只会看到文件莫名消失。
                log.warn(
                        "Session file {} discarded: the deserialization filter rejected class '{}'. "
                                + "If you store that type, add it (or its package) to {}.",
                        path, rejected, PropertiesConstant.SERVLET_SESSION_PERSISTENT_DESERIALIZATION_FILTER);
            } else {
                log.warn("Session file {} discarded (corrupted or class-version incompatible): {}", path, e.toString());
            }
        }
        deleteFileQuietly(path);
        return null;
    }

    /**
     * 包一层过滤器，记下本次读取中第一个被拒绝的类名。
     * <p>
     * 必要性：JEP 290 拒绝时抛的是普通 {@link java.io.InvalidClassException}（消息含 {@code filter status: REJECTED}）， 与「文件损坏 /
     * serialVersionUID 不匹配」抛的异常类型<b>完全相同</b>。靠 JDK 消息文本区分太脆弱，故自行记录类名 —— 用户在收紧白名单时能看到该放行哪个类，而不是只看到一行「文件损坏」。
     * </p>
     */
    static ObjectInputFilter recordingRejections(ObjectInputFilter delegate, AtomicReference<String> rejectedClass) {
        return info -> {
            ObjectInputFilter.Status status = delegate.checkInput(info);
            if (status == ObjectInputFilter.Status.REJECTED) {
                Class<?> serialClass = info.serialClass();
                rejectedClass.compareAndSet(null, serialClass != null ? serialClass.getName() : "unknown");
            }
            return status;
        };
    }

    @Override
    @Nullable
    public HttpSessionData getSession(String sessionId) {
        HttpSessionData session = sessions.get(sessionId);
        if (session != null && session.isExpired(System.currentTimeMillis())) {
            // 过期清理同样要在写锁下进行：否则清掉文件后会被并发的 saveSession 写回。
            ReentrantLock lock = writeLockFor(sessionId);
            lock.lock();
            try {
                if (sessions.remove(sessionId, session)) {
                    deleteFileQuietly(fileOf(sessionId));
                }
            } finally {
                lock.unlock();
            }
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

    /** 会话 id 恒定映射到同一把条带锁，保证「同一会话」的磁盘写严格串行。 */
    private ReentrantLock writeLockFor(String sessionId) {
        return writeLocks[Math.floorMod(sessionId.hashCode(), WRITE_LOCK_STRIPES)];
    }

    /**
     * 落盘会话：过滤排除名单与不可序列化属性后，原子写入 {@code <id>.session}。 写入失败只 warn，不影响请求处理。
     * <p>
     * 同一会话的写串行化；tmp 名带上线程号，锁万一被绕过也不会写坏彼此的目标文件。
     * </p>
     */
    @Override
    public void saveSession(HttpSessionData session) {
        if (session == null || session.isInvalid()) {
            return;
        }
        String id = session.getId();
        ReentrantLock lock = writeLockFor(id);
        lock.lock();
        try {
            // 内存更新与写盘必须在同一临界区，且只对**在册**会话生效。put 原先在锁外，会把
            // removeSession（invalidate 的落点）刚摘除的条目重新放回内存，紧随的这次写盘就把它复活了；
            // 不在册（已被摘除或已被过期清理）的会话一律不再接纳。
            if (session.isInvalid() || !sessions.containsKey(id)) {
                return;
            }
            sessions.put(id, session);
            byte[] payload = serialize(session);
            if (payload == null) {
                return;
            }
            Path target = fileOf(id);
            // 同一会话的落盘已由条带锁互斥，tmp 名带上线程 id 属多余（且会让遗留 tmp 的数量随线程数放大）
            Path tmp = storeDir.resolve(id + TMP_SUFFIX);
            try {
                Files.write(tmp, payload);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                log.warn("Failed to persist session {} to {}: {}", id, target, e.toString());
                deleteFileQuietly(tmp);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 序列化会话：排除名单命中或不可序列化的属性被跳过（warn），其余正常写入。 整体序列化异常时返回 null（放弃本次落盘）。
     */
    @Nullable
    private byte[] serialize(HttpSessionData session) {
        // 构造可序列化的副本：跳过排除名单与不可序列化属性
        HttpSessionData copy = new HttpSessionData(session.getId(), session.getCreationTime());
        copy.setLastAccessedTime(session.getLastAccessedTime());
        copy.setMaxInactiveInterval(session.getMaxInactiveInterval());
        for (Map.Entry<String, Object> entry : session.getAttributes().entrySet()) {
            String name = entry.getKey();
            // 属性名不可能为 null，但 Map 允许 null 键：跳过而不是把它传给要求非空的 setAttribute
            if (name == null) {
                continue;
            }
            Object value = entry.getValue();
            // Servlet 语义：null 值等同于「移除该属性」；且 HttpSessionData 内部是 ConcurrentHashMap，
            // 它不接受 null value（put 会抛 NPE），故在入口显式跳过
            if (value == null) {
                continue;
            }
            if (excludeAttributes.contains(name)) {
                continue;
            }
            if (!(value instanceof Serializable)) {
                log.warn("Session attribute '{}' is not serializable ({}), skipped from persistence", name,
                        value.getClass().getName());
                continue;
            }
            // 走到这里 value 已被确认可序列化：原先第二处 instanceof 因此恒真
            // （BC_VACUOUS_INSTANCEOF），改为直接校验
            if (!isActuallySerializable((Serializable) value)) {
                log.warn("Session attribute '{}' ({}) failed serialization check, skipped from persistence", name,
                        value.getClass().getName());
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
        // 与 saveSession 同一把锁：摘除内存 + 删文件必须一起挡住并发的落盘，否则删除会被紧随的 move 抵消。
        ReentrantLock lock = writeLockFor(sessionId);
        lock.lock();
        try {
            sessions.remove(sessionId);
            deleteFileQuietly(fileOf(sessionId));
        } finally {
            lock.unlock();
        }
    }

    /** 当前内存中未过期的会话数。 */
    public int getActiveSessionCount() {
        long now = System.currentTimeMillis();
        return (int) sessions.values().stream().filter(s -> !s.isExpired(now)).count();
    }

    public Path getStoreDir() {
        return storeDir;
    }

    /**
     * 会话 id → 落盘路径。
     * <p>
     * <b>路径安全收口</b>：id 的来源（{@code JSESSIONID} cookie、{@code ;jsessionid=} URL）是客户端可控的， 因此在这里就地保证「id
     * 绝不参与路径解析」。今天的调用点本来都只会传自己生成的 id （{@code generateSessionId()} 产出纯 hex）——但那是跨方法的隐式约定，一旦将来引入「按请求懒加载」之类的改动就会变成 任意文件读 /
     * 删除 / 反序列化原语。把校验放在此处后，该性质成为 <b>局部不变量</b>：越界一律失败，且失败发生在任何文件操作之前。
     * </p>
     *
     * @throws IllegalArgumentException
     *             id 为空、含路径分隔符或上跳片段、或解析后越出 store 目录
     */
    private Path fileOf(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IllegalArgumentException("Session id must not be empty");
        }
        if (sessionId.indexOf('/') >= 0 || sessionId.indexOf('\\') >= 0 || sessionId.contains("..")) {
            throw new IllegalArgumentException("Unsafe session id: " + sessionId);
        }
        Path path = storeDir.resolve(sessionId + FILE_SUFFIX).normalize();
        if (!path.startsWith(storeDirNormalized)) {
            throw new IllegalArgumentException("Session id escapes the store dir: " + sessionId);
        }
        return path;
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
                sweepExpired(System.currentTimeMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * 清理一轮过期会话：逐条「条件摘除内存条目 + 删文件」，两步必须在同一把写锁内完成。
     * <p>
     * <b>为什么不能用 {@code sessions.values().removeIf(...)}</b>：{@code removeIf} 的谓词返回 true 后由 CHM
     * 自己摘除条目，即「删文件」与「摘除内存」被拆到了两个时刻。删完文件、条目尚未摘除的窗口里， 并发 {@link #saveSession} 能通过 {@code !sessions.containsKey(id)}
     * 守卫（过期会话不属于 {@code isInvalid()}），把刚删掉的文件连同已刷新的 {@code lastAccessedTime} 一起写回； 重启时 {@code loadExistingSessions}
     * 便把它当未过期会话加载 —— <b>过期会话复活</b>。 改为锁内条件移除（与 {@link #getSession} 的过期清理同构）后，摘除成功才删文件，窗口消失。
     * </p>
     * <p>
     * 包级可见：便于单测直接驱动（清理线程的间隔是常量，隔着一层无法确定性测试）。
     * </p>
     */
    void sweepExpired(long now) {
        // 用 entrySet 遍历：直接拿到 value，避免 keySet + get 的双重查找（SpotBugs WMI_WRONG_MAP_ITERATOR）。
        // 移除仍走 sessions.remove(key, value) 的条件式 API（不在迭代器上 remove），CHM 的弱一致迭代器允许这样用。
        for (Map.Entry<String, HttpSessionData> entry : sessions.entrySet()) {
            String id = entry.getKey();
            HttpSessionData session = entry.getValue();
            if (session == null || !session.isExpired(now)) {
                continue;
            }
            ReentrantLock lock = writeLockFor(id);
            lock.lock();
            try {
                if (sessions.remove(id, session)) {
                    deleteFileQuietly(fileOf(id));
                }
            } finally {
                lock.unlock();
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

    /**
     * 把 {@link ObjectInputFilter.Config#createFilter(String)} 规格解析为过滤器：空 / 全空白 → {@code null}（不过滤，
     * 与历史行为一致）。规格非法时**抛异常**而不是静默放过：这是收紧项，写错必须显形，否则会以为已加固而其实没有。
     */
    @Nullable
    static ObjectInputFilter parseDeserializationFilter(@Nullable String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            return null;
        }
        try {
            return ObjectInputFilter.Config.createFilter(spec.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid session deserialization filter spec: " + spec, e);
        }
    }

    /** 供测试：当前内存中的会话快照（id -> data）。 */
    Map<String, HttpSessionData> snapshot() {
        return new HashMap<>(sessions);
    }
}
