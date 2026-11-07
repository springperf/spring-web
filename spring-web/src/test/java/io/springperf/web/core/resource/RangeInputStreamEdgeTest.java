package io.springperf.web.core.resource;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.springperf.web.core.resource.ResourceRequestHandler.RangeInputStream;

/** 206 区间流的 read/skip/available 契约与「跳到区间起点」兜底分支。 */
class RangeInputStreamEdgeTest {

    private static RangeInputStream stream(String content, long start, long length) {
        return new RangeInputStream(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), start, length);
    }

    @Test
    void readSingleByteHonorsStartAndLength() throws IOException {
        RangeInputStream in = stream("0123456789", 3, 4);

        assertEquals('3', in.read());
        assertEquals('4', in.read());
        assertEquals('5', in.read());
        assertEquals('6', in.read());
        assertEquals(-1, in.read(), "区间读满后必须返回 -1");
    }

    @Test
    void readBulkHonorsRemainingLength() throws IOException {
        RangeInputStream in = stream("0123456789", 2, 3);
        byte[] buf = new byte[10];

        assertEquals(3, in.read(buf, 0, 10));
        assertEquals("234", new String(buf, 0, 3, StandardCharsets.UTF_8));
        assertEquals(-1, in.read(buf, 0, 10));
    }

    @Test
    void zeroLengthBulkReadReturnsZeroEvenAfterExhaustion() throws IOException {
        RangeInputStream in = stream("0123456789", 2, 1);
        byte[] buf = new byte[4];
        assertEquals('2', in.read());

        assertEquals(0, in.read(buf, 0, 0), "len==0 契约：区间已结束也应返回 0");
    }

    @Test
    void readBeyondUnderlyingEndReturnsMinusOne() throws IOException {
        RangeInputStream in = stream("abc", 10, 3);

        assertEquals(-1, in.read());
        assertEquals(-1, in.read(new byte[4], 0, 4));
    }

    @Test
    void skipHonorsStartAndRemaining() throws IOException {
        RangeInputStream in = stream("0123456789", 2, 5);

        assertEquals(0, in.skip(0));
        assertEquals(0, in.skip(-1));
        assertEquals(2, in.skip(2), "先跳到区间起点后才允许跳过 n 字节");
        assertEquals('4', in.read());
        assertEquals(2, in.skip(10), "skip 不得超过区间剩余长度");
        assertEquals(-1, in.read());
    }

    @Test
    void skipWhenStartBeyondEndReturnsZero() throws IOException {
        assertEquals(0, stream("abc", 10, 3).skip(3));
    }

    @Test
    void availableIsZeroWhileStartPending() throws IOException {
        RangeInputStream in = stream("0123456789", 3, 4);

        assertEquals(0, in.available(), "尚未跳到区间起点时不应上报可读字节");
        assertEquals('3', in.read());
        assertEquals(3, in.available());
    }

    @Test
    void skipFullyFallsBackToSingleByteReadWhenDelegateCannotSkip() throws IOException {
        InputStream noSkip = new ByteArrayInputStream("abcdef".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public long skip(long n) {
                return 0;
            }
        };
        RangeInputStream in = new RangeInputStream(noSkip, 2, 2);

        assertEquals('c', in.read());
        assertEquals('d', in.read());
        assertEquals(-1, in.read());
    }

    @Test
    void readSingleByteWhenDelegateExhaustedDuringSkipReturnsMinusOne() throws IOException {
        assertEquals(-1, stream("", 1, 2).read());
        assertEquals(-1, stream("", 1, 2).read(new byte[2], 0, 2));
    }
}
