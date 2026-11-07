package io.springperf.web.core.filter;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 访问日志清理与落盘失败的容错边界。 */
class AccessLogWriterEdgeTest {

    @TempDir
    Path tempDir;

    private static Path touch(Path dir, String name) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, "old".getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void cleanupDeletesOnlyExpiredFilesOwnedByThisWriter() throws IOException {
        Path expired = touch(tempDir, "access20200101.log");
        Path future = touch(tempDir, "access20991231.log");
        Path foreign = touch(tempDir, "notes.txt");
        Path malformed = touch(tempDir, "access-not-a-date.log");
        Path wrongSuffix = touch(tempDir, "access20200101.txt");

        new AccessLogWriter(tempDir, "access", ".log", true, 7).write("line");

        assertFalse(Files.exists(expired), "超过保留天数的轮转文件应被删除");
        assertTrue(Files.exists(future), "未过期的轮转文件应保留");
        assertTrue(Files.exists(foreign), "非本 writer 命名的文件不应误删");
        assertTrue(Files.exists(malformed), "日期非法的同名格式文件不应误删");
        assertTrue(Files.exists(wrongSuffix), "后缀不匹配的文件不应误删");
        assertTrue(Files.exists(tempDir.resolve(
                "access" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".log")));
    }

    @Test
    void noRotateKeepsExpiredFilesUntouched() throws IOException {
        Path expired = touch(tempDir, "access20200101.log");

        AccessLogWriter writer = new AccessLogWriter(tempDir, "access", ".log", false, 7);
        writer.write("line");
        writer.close();

        assertTrue(Files.exists(expired));
        assertTrue(Files.exists(tempDir.resolve("access.log")), "非轮转模式使用固定文件名");
    }

    @Test
    void maxDaysDisabledKeepsExpiredFiles() throws IOException {
        Path expired = touch(tempDir, "access20200101.log");

        AccessLogWriter writer = new AccessLogWriter(tempDir, "access", ".log", true, 0);
        writer.write("line");
        writer.close();

        assertTrue(Files.exists(expired), "maxDays<=0 表示不限制保留天数");
    }

    @Test
    void directoryCreationFailureIsSwallowed() throws IOException {
        Path blocked = tempDir.resolve("blocked");
        Files.write(blocked, "not a directory".getBytes(StandardCharsets.UTF_8));

        AccessLogWriter writer = new AccessLogWriter(blocked, "access", ".log", true, 7);
        assertDoesNotThrow(() -> writer.write("line"), "落盘失败不应影响请求处理");
        assertFalse(Files.isDirectory(blocked));
        writer.close();
    }

    @Test
    void closeReleasesWriterAndAllowsReopen() throws IOException {
        AccessLogWriter writer = new AccessLogWriter(tempDir, "access", ".log", false, 7);
        writer.write("first");
        writer.close();
        writer.write("second");
        writer.close();

        assertEquals(java.util.Arrays.asList("first", "second"),
                Files.readAllLines(tempDir.resolve("access.log"), StandardCharsets.UTF_8));
    }
}
