package io.springperf.web.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DataSizeUtils} 单元测试：Spring DataSize 风格解析。
 */
class DataSizeUtilsTest {

    @Test
    void bareNumber_isBytes() {
        assertEquals(1024L, DataSizeUtils.parseBytes("1024", "k"));
    }

    @Test
    void binaryUnits() {
        assertEquals(1024L, DataSizeUtils.parseBytes("1KB", "k"));
        assertEquals(1024L, DataSizeUtils.parseBytes("1k", "k"));
        assertEquals(1024L * 1024, DataSizeUtils.parseBytes("1MB", "k"));
        assertEquals(1024L * 1024 * 1024, DataSizeUtils.parseBytes("1GB", "k"));
        assertEquals(1024L * 1024 * 1024 * 1024, DataSizeUtils.parseBytes("1TB", "k"));
    }

    @Test
    void fractional() {
        assertEquals((long) (1.5 * 1024 * 1024), DataSizeUtils.parseBytes("1.5MB", "k"));
    }

    @Test
    void whitespaceAndCaseInsensitive() {
        assertEquals(2048L, DataSizeUtils.parseBytes(" 2 kb ", "k"));
        assertEquals(2048L, DataSizeUtils.parseBytes("2KB", "k"));
    }

    @Test
    void bytesSuffix() {
        assertEquals(512L, DataSizeUtils.parseBytes("512B", "k"));
        assertEquals(512L, DataSizeUtils.parseBytes("512bytes", "k"));
    }

    @Test
    void invalidFormat_throws() {
        assertThrows(IllegalArgumentException.class, () -> DataSizeUtils.parseBytes("abc", "k"));
        assertThrows(IllegalArgumentException.class, () -> DataSizeUtils.parseBytes("10XB", "k"));
        assertThrows(IllegalArgumentException.class, () -> DataSizeUtils.parseBytes("", "k"));
        assertThrows(IllegalArgumentException.class, () -> DataSizeUtils.parseBytes(null, "k"));
    }

    @Test
    void parseBytesOrDefault_returnsDefaultOnInvalid() {
        assertEquals(42L, DataSizeUtils.parseBytesOrDefault("bogus", 42L));
        assertEquals(42L, DataSizeUtils.parseBytesOrDefault(null, 42L));
        assertEquals(42L, DataSizeUtils.parseBytesOrDefault("  ", 42L));
        assertEquals(1024L, DataSizeUtils.parseBytesOrDefault("1KB", 42L));
    }
}
