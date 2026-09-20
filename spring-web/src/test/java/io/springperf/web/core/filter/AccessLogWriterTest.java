package io.springperf.web.core.filter;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccessLogWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void fromProperties_noDirectory_returnsNoop() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get(PropertiesConstant.ACCESSLOG_DIRECTORY, null)).thenReturn(null);
        AccessLogWriter writer = AccessLogWriter.fromProperties(props);
        assertTrue(writer.isNoop());
        assertSame(AccessLogWriter.NOOP, writer);
        // NOOP 写入是空操作，不抛异常
        assertDoesNotThrow(() -> writer.write("line"));
    }

    @Test
    void fromProperties_blankDirectory_returnsNoop() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get(PropertiesConstant.ACCESSLOG_DIRECTORY, null)).thenReturn("   ");
        assertTrue(AccessLogWriter.fromProperties(props).isNoop());
    }

    @Test
    void write_appendsLinesToRotatedFile() throws IOException {
        AccessLogWriter writer = new AccessLogWriter(tempDir, "access", ".log", true, 7);
        writer.write("first");
        writer.write("second");
        writer.close();

        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        Path file = tempDir.resolve("access" + today + ".log");
        assertTrue(Files.exists(file), "应生成按天轮转文件 " + file);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(List.of("first", "second"), lines);
    }

    @Test
    void write_noRotate_singleFile() throws IOException {
        AccessLogWriter writer = new AccessLogWriter(tempDir, "access", ".log", false, 7);
        writer.write("hello");
        writer.close();

        Path file = tempDir.resolve("access.log");
        assertTrue(Files.exists(file));
        assertEquals("hello", Files.readString(file, StandardCharsets.UTF_8).trim());
    }

    @Test
    void write_customPrefixSuffix() throws IOException {
        AccessLogWriter writer = new AccessLogWriter(tempDir, "req-", ".txt", false, 7);
        writer.write("x");
        writer.close();
        assertTrue(Files.exists(tempDir.resolve("req-.txt")));
    }

    @Test
    void cleanup_deletesFilesOlderThanMaxDays() throws IOException {
        // 预置一个 30 天前的轮转文件 + 一个非本 writer 命名文件
        String old = LocalDate.now().minusDays(30).format(DateTimeFormatter.BASIC_ISO_DATE);
        Path oldFile = tempDir.resolve("access" + old + ".log");
        Files.writeString(oldFile, "old\n");
        Path foreign = tempDir.resolve("other20200101.log");
        Files.writeString(foreign, "keep\n");

        AccessLogWriter writer = new AccessLogWriter(tempDir, "access", ".log", true, 7);
        writer.write("today");
        writer.close();

        assertFalse(Files.exists(oldFile), "超过 max-days 的轮转文件应被清理");
        assertTrue(Files.exists(foreign), "非本 writer 命名的文件不应被删除");
    }

    @Test
    void cleanup_maxDaysZero_keepsAll() throws IOException {
        String old = LocalDate.now().minusDays(30).format(DateTimeFormatter.BASIC_ISO_DATE);
        Path oldFile = tempDir.resolve("access" + old + ".log");
        Files.writeString(oldFile, "old\n");

        AccessLogWriter writer = new AccessLogWriter(tempDir, "access", ".log", true, 0);
        writer.write("today");
        writer.close();

        assertTrue(Files.exists(oldFile), "max-days<=0 表示不限制，旧文件保留");
    }

    @Test
    void write_createsMissingDirectory() {
        Path nested = tempDir.resolve("nested/logs");
        AccessLogWriter writer = new AccessLogWriter(nested, "access", ".log", false, 7);
        assertDoesNotThrow(() -> writer.write("x"));
        writer.close();
        assertTrue(Files.exists(nested.resolve("access.log")));
    }
}
