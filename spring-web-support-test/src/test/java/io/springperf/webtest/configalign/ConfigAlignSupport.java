package io.springperf.webtest.configalign;

import java.util.Arrays;

/**
 * configalign 测试的 JDK 8 适配工具。
 *
 * <p>master 用 JDK 11 的 {@link String#repeat(int)} 构造定长载荷；本分支源码级 JDK 8，且
 * Spring 5.3 的 {@code org.springframework.util.StringUtils} <b>没有</b> {@code repeat(String,int)}
 * （该方法自 Spring 6 起才提供，已用 {@code javap} 对 5.3.31 产物实测确认），故以等价实现替代。</p>
 */
final class ConfigAlignSupport {

    private ConfigAlignSupport() {
    }

    /** 等价于 JDK 11 的 {@code String.valueOf(c).repeat(count)}。 */
    static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }
}
