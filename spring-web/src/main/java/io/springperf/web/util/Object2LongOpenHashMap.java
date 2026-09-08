package io.springperf.web.util;

/**
 * 专用于 String→long 的开放寻址哈希表。
 * 参考 fastutil 6.5.6 Object2LongOpenHashMap 的核心算法裁剪而来。
 * <p>
 * 仅保留 put / get / containsKey，无迭代器、无序列化。
 * null 键不可用（null 作为空槽标记）。
 */
public class Object2LongOpenHashMap {

    private static final int DEFAULT_CAPACITY = 16;
    private static final float DEFAULT_LOAD_FACTOR = 0.75f;
    private static final long DEFAULT_RETURN_VALUE = 0L;

    /** 键数组，null 表示空槽 */
    private transient String[] key;
    /** 值数组 */
    private transient long[] value;
    /** 当前元素数 */
    private int size;
    /** 掩码 = capacity - 1 */
    private int mask;
    /** 触发扩容的阈值 = capacity * loadFactor 向上取整 */
    private int maxFill;
    private float loadFactor;

    public Object2LongOpenHashMap() {
        this(DEFAULT_CAPACITY, DEFAULT_LOAD_FACTOR);
    }

    public Object2LongOpenHashMap(int capacity) {
        this(capacity, DEFAULT_LOAD_FACTOR);
    }

    public Object2LongOpenHashMap(int capacity, float loadFactor) {
        if (loadFactor <= 0 || loadFactor > 1) {
            throw new IllegalArgumentException("loadFactor must be in (0, 1]");
        }
        this.loadFactor = loadFactor;
        int actualCapacity = arraySize(capacity, loadFactor);
        this.mask = actualCapacity - 1;
        this.maxFill = maxFill(actualCapacity, loadFactor);
        this.key = new String[actualCapacity];
        this.value = new long[actualCapacity];
    }

    // ========== 公共方法 ==========

    public long put(String k, long v) {
        if (k == null) {
            throw new IllegalArgumentException("Null key is not allowed");
        }
        int pos = find(k);
        if (key[pos] == null) {
            // 新增
            key[pos] = k;
            value[pos] = v;
            if (++size > maxFill) {
                rehash(arraySize(size + 1, loadFactor));
            }
            return DEFAULT_RETURN_VALUE;
        }
        // 更新
        long old = value[pos];
        value[pos] = v;
        return old;
    }

    public long get(String k) {
        if (k == null) {
            return DEFAULT_RETURN_VALUE;
        }
        int pos = find(k);
        return key[pos] == null ? DEFAULT_RETURN_VALUE : value[pos];
    }

    public boolean containsKey(String k) {
        if (k == null) {
            return false;
        }
        return key[find(k)] != null;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    // ========== 内部方法 ==========

    /**
     * 查找 key 的位置。
     * 返回的槽位要么是 key 已存在的位置，要么是一个 null 空槽（可插入）。
     */
    private int find(String k) {
        int pos = mix(k.hashCode()) & mask;
        String curr;
        // 线性探测
        while ((curr = key[pos]) != null) {
            if (curr.equals(k)) {
                break;
            }
            pos = (pos + 1) & mask;
        }
        return pos;
    }

    /** 混合哈希值 */
    private static int mix(int h) {
        h *= 0x9E3779B9;
        return h ^ (h >>> 16);
    }

    /** 扩容 */
    private void rehash(int newCapacity) {
        String[] oldKey = key;
        long[] oldValue = value;

        key = new String[newCapacity];
        value = new long[newCapacity];
        mask = newCapacity - 1;
        maxFill = maxFill(newCapacity, loadFactor);
        size = 0;

        for (int i = oldKey.length; i-- > 0; ) {
            String k = oldKey[i];
            if (k != null) {
                put(k, oldValue[i]);
            }
        }
    }

    /** 计算满足 capacity 所需的最小 2^N 数组大小 */
    private static int arraySize(int expected, float f) {
        long s = Math.max(2, nextPowerOfTwo((long) Math.ceil(expected / f)));
        if (s > (1 << 30)) {
            throw new IllegalStateException("Too large (" + expected + " expected elements with load factor " + f + ")");
        }
        return (int) s;
    }

    /** 计算 maxFill */
    private static int maxFill(int capacity, float loadFactor) {
        return Math.min((int) Math.ceil(capacity * loadFactor), capacity - 1);
    }

    /** 返回 ≥ v 的最小 2^N */
    private static int nextPowerOfTwo(long v) {
        // 对于 ≤ 1 的情况返回 2
        if (v <= 1) return 2;
        // 已经是 2^N
        if ((v & (v - 1)) == 0) return (int) v;
        // 找最高位 1，左移一位
        int n = 64 - Long.numberOfLeadingZeros(v - 1);
        return 1 << n;
    }
}
