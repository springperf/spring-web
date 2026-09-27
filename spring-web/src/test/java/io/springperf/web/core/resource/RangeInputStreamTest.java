package io.springperf.web.core.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * {@link ResourceRequestHandler.RangeInputStream} 的区间语义：{@code [start, start+length)}。
 * <p>
 * 重点是 {@code skip()} —— 修复前它在「尚未跳到区间起点」时把 n 原样交给底层，既不推进起点也不扣减剩余长度， 于是随后一次 {@code read()} 会再跳一遍起点，读到的字节比调用方预期的更靠后（数据错位），
 * 且返回值可以超过区间长度，违反 {@code InputStream#skip} 的契约。
 * </p>
 */
class RangeInputStreamTest {

    private static final byte[] DATA = "0123456789".getBytes(StandardCharsets.US_ASCII);

    private static ResourceRequestHandler.RangeInputStream range(long start, long length) {
        return new ResourceRequestHandler.RangeInputStream(new ByteArrayInputStream(DATA), start, length);
    }

    @Test
    void read_onlyReturnsTheRequestedWindow() throws Exception {
        ResourceRequestHandler.RangeInputStream in = range(2, 3); // "234"
        assertEquals('2', in.read());
        assertEquals('3', in.read());
        assertEquals('4', in.read());
        assertEquals(-1, in.read(), "超出区间应返回 EOF");
    }

    @Test
    void skip_afterStartAlreadyConsumed_advancesWithinWindow() throws Exception {
        ResourceRequestHandler.RangeInputStream in = range(2, 5); // "23456"
        assertEquals('2', in.read());
        assertEquals(2, in.skip(2), "跳过 '34'");
        assertEquals('5', in.read(), "应落在 '5'");
    }

    /**
     * 回归：修复前 {@code toSkip>0} 时 skip 只透传给底层、不推进起点， 随后 read() 会再跳一次起点，于是读到 '4' 而不是 '6'。
     */
    @Test
    void skip_beforeStartIsReached_skipsToStartFirst() throws Exception {
        ResourceRequestHandler.RangeInputStream in = range(4, 3); // "456"
        assertEquals(2, in.skip(2), "先跳到底层 offset 4，再跳过 '45'");
        assertEquals('6', in.read(), "应落在区间最后一个字节");
    }

    @Test
    void skip_beyondRemaining_isClampedToRemaining() throws Exception {
        ResourceRequestHandler.RangeInputStream in = range(0, 3);
        assertEquals(3, in.skip(100), "最多只能跳过区间内的 3 个字节");
        assertEquals(-1, in.read(), "已无剩余");
    }

    @Test
    void skip_nonPositive_returnsZeroWithoutConsuming() throws Exception {
        ResourceRequestHandler.RangeInputStream in = range(1, 3);
        assertEquals(0, in.skip(0));
        assertEquals(0, in.skip(-5));
        assertEquals('1', in.read(), "非正数 skip 不应消耗任何字节");
    }

    @Test
    void skip_afterWindowExhausted_returnsZero() throws Exception {
        ResourceRequestHandler.RangeInputStream in = range(0, 1);
        assertEquals('0', in.read());
        assertEquals(0, in.skip(5), "区间已耗尽时不可再跳");
    }

    /**
     * 契约明文（{@code InputStream#read(byte[], int, int)}）：{@code len == 0} 时不做任何读取、返回 0， 这与该流是否已到区间末尾**无关**。修复前窗口耗尽后返回
     * -1，属于契约违反。
     */
    @Test
    void read_zeroLength_returnsZeroEvenAtEndOfWindow() throws Exception {
        ResourceRequestHandler.RangeInputStream in = range(0, 1);
        byte[] buf = new byte[4];

        assertEquals(0, in.read(buf, 0, 0), "窗口未耗尽时 len=0 返回 0");
        assertEquals('0', in.read());
        assertEquals(-1, in.read(), "窗口已耗尽");
        assertEquals(0, in.read(buf, 0, 0), "窗口耗尽后 len=0 仍必须返回 0 而不是 -1");
    }
}
