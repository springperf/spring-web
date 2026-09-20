package io.springperf.web.core.filter;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 访问日志落盘写出器（对齐 Tomcat {@code server.accesslog.*} 落盘部分）：
 * 按文件 {@code {directory}/{prefix}{date}{suffix}} 追加写入，支持按天轮转与保留天数清理。
 *
 * <p>落盘为可选能力：未配置 {@code server.accesslog.directory} 时使用
 * {@link AccessLogWriter#NOOP}，仅走日志框架（保持既有行为）。</p>
 *
 * <p>并发：{@link #write(String)} 由多请求线程调用，以 {@code synchronized} 串行化写入与轮转检查。</p>
 */
@Slf4j
public class AccessLogWriter {

    /** 不落盘的写出器（未配置目录）。 */
    public static final AccessLogWriter NOOP = new AccessLogWriter() {
    };

    private final Path directory;
    private final String prefix;
    private final String suffix;
    private final boolean rotate;
    private final int maxDays;

    private Writer writer;
    private LocalDate currentDate;
    private long lastCleanupEpochDay = Long.MIN_VALUE;

    /** NOOP 构造：不落盘。 */
    protected AccessLogWriter() {
        this.directory = null;
        this.prefix = null;
        this.suffix = null;
        this.rotate = false;
        this.maxDays = 0;
    }

    public AccessLogWriter(Path directory, String prefix, String suffix, boolean rotate, int maxDays) {
        this.directory = directory;
        this.prefix = prefix != null ? prefix : PropertiesConstant.ACCESSLOG_PREFIX_DEFAULT;
        this.suffix = suffix != null ? suffix : PropertiesConstant.ACCESSLOG_SUFFIX_DEFAULT;
        this.rotate = rotate;
        this.maxDays = maxDays;
    }

    /**
     * 从配置创建落盘写出器：{@code server.accesslog.directory} 为空时返回 {@link #NOOP}。
     */
    public static AccessLogWriter fromProperties(ApplicationProperties props) {
        String dir = props.get(PropertiesConstant.ACCESSLOG_DIRECTORY, null);
        if (dir == null || dir.trim().isEmpty()) {
            return NOOP;
        }
        return new AccessLogWriter(
                Paths.get(dir.trim()),
                props.get(PropertiesConstant.ACCESSLOG_PREFIX, PropertiesConstant.ACCESSLOG_PREFIX_DEFAULT),
                props.get(PropertiesConstant.ACCESSLOG_SUFFIX, PropertiesConstant.ACCESSLOG_SUFFIX_DEFAULT),
                props.getBoolean(PropertiesConstant.ACCESSLOG_ROTATE, PropertiesConstant.ACCESSLOG_ROTATE_DEFAULT),
                props.getInt(PropertiesConstant.ACCESSLOG_MAX_DAYS));
    }

    public boolean isNoop() {
        return directory == null;
    }

    /**
     * 写入一行访问日志（自动补换行）。落盘失败不应影响请求处理，仅记录告警。
     */
    public synchronized void write(String line) {
        if (isNoop()) {
            return;
        }
        try {
            LocalDate today = LocalDate.now();
            ensureWriter(today);
            writer.write(line);
            writer.write('\n');
            writer.flush();
            cleanupIfNeeded(today);
        } catch (IOException e) {
            log.warn("access log write failed", e);
        }
    }

    /** 关闭底层 writer（应用关闭时调用）。 */
    public synchronized void close() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                log.debug("access log close failed", e);
            }
            writer = null;
        }
    }

    private void ensureWriter(LocalDate today) throws IOException {
        boolean needOpen = writer == null
                || (rotate && !today.equals(currentDate));
        if (needOpen) {
            if (writer != null) {
                writer.close();
            }
            Files.createDirectories(directory);
            Path file = directory.resolve(fileName(today));
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            currentDate = today;
        }
    }

    private String fileName(LocalDate date) {
        if (rotate) {
            return prefix + date.format(DateTimeFormatter.BASIC_ISO_DATE) + suffix;
        }
        return prefix + suffix;
    }

    /** 每日最多清理一次：删除超过 {@code max-days} 的轮转文件。{@code maxDays<=0} 表示不限制。 */
    private void cleanupIfNeeded(LocalDate today) {
        if (!rotate || maxDays <= 0 || isNoop()) {
            return;
        }
        long todayEpochDay = today.toEpochDay();
        if (lastCleanupEpochDay == todayEpochDay) {
            return;
        }
        lastCleanupEpochDay = todayEpochDay;
        LocalDate cutoff = today.minusDays(maxDays);
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> toDelete = new ArrayList<>();
            files.filter(Files::isRegularFile).forEach(p -> {
                LocalDate fileDate = parseDate(p.getFileName().toString());
                if (fileDate != null && fileDate.isBefore(cutoff)) {
                    toDelete.add(p);
                }
            });
            toDelete.sort(Comparator.naturalOrder());
            for (Path p : toDelete) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.debug("access log cleanup failed for {}", p, e);
                }
            }
        } catch (IOException e) {
            log.debug("access log cleanup listing failed", e);
        }
    }

    /** 从文件名解析日期（{prefix}{yyyyMMdd}{suffix}）；非本 writer 命名的文件返回 null（不误删）。 */
    private LocalDate parseDate(String fileName) {
        if (!fileName.startsWith(prefix) || !fileName.endsWith(suffix)) {
            return null;
        }
        String middle = fileName.substring(prefix.length(), fileName.length() - suffix.length());
        try {
            return LocalDate.parse(middle, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (Exception e) {
            return null;
        }
    }
}
