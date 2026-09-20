package io.springperf.web.support.servlet.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code server.servlet.session.cookie.same-site} 的规范化：
 * 必须产出 Netty {@code CookieHeaderNames.SameSite} 的精确常量名（`Lax`/`Strict`/`None`，非全大写），
 * 否则配置 `strict` 时整体大写会抛 {@code IllegalArgumentException}（历史缺陷）。
 */
class PerfHttpSessionManagerSameSiteTest {

    @Test
    void lowerCaseConfig_mapsToExactEnumName() {
        assertThat(PerfHttpSessionManager.canonicalSameSite("lax")).isEqualTo("Lax");
        assertThat(PerfHttpSessionManager.canonicalSameSite("strict")).isEqualTo("Strict");
        assertThat(PerfHttpSessionManager.canonicalSameSite("none")).isEqualTo("None");
    }

    @Test
    void mixedCaseAndWhitespace_accepted() {
        assertThat(PerfHttpSessionManager.canonicalSameSite("  STRICT ")).isEqualTo("Strict");
        assertThat(PerfHttpSessionManager.canonicalSameSite("None")).isEqualTo("None");
    }

    @Test
    void unknownValue_ignored() {
        assertThat(PerfHttpSessionManager.canonicalSameSite("bogus")).isNull();
        assertThat(PerfHttpSessionManager.canonicalSameSite("")).isNull();
    }
}
