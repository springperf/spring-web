package io.springperf.web.util.support;

public enum SegKind {
    LITERAL,        // 固定字符串
    SINGLE_WILDCARD,// * 或 {var}，单 segment
    MULTI_WILDCARD, // **，跨 segment
    REGEX           // 正则（只用于 membership）
}