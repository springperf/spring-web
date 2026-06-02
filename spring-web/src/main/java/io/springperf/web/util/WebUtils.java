package io.springperf.web.util;

public class WebUtils {

    /**
     * 拼接两个 URL 路径，确保结果以 / 开头，不以 / 结尾。
     * 例如：
     * join("/api/", "/user/") => "/api/user"
     * join("api", "user") => "/api/user"
     * join(null, "test/") => "/test"
     * join("/api", null) => "/api"
     */
    public static String pathJoin(String part1, String part2) {
        // 1. 安全处理 null 或空白
        String p1 = normalize(part1);
        String p2 = normalize(part2);

        if (p1.isEmpty() && p2.isEmpty()) {
            return "/";
        }

        // 2. 去除前后多余的斜杠（中间再拼接时不会出现重复斜杠）
        if (!p1.isEmpty() && !p2.isEmpty()) {
            return formatPath(p1) + formatPath(p2);
        } else if (!p1.isEmpty()) {
            return formatPath(p1);
        } else {
            return formatPath(p2);
        }
    }

    /**
     * 规范化输入字符串：去掉首尾空格，防止 null。
     */
    public static String normalize(String part) {
        return part == null ? "" : part.trim();
    }

    /**
     * 去掉字符串尾部的斜杠并保证首部一定有斜杠。
     */
    public static String formatPath(String path) {
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * 返回字符串中所有 '/' 的位置下标。
     *
     * @param url 输入字符串，可为 null
     * @return 所有 '/' 的索引坐标数组，如果没有则返回长度为 0 的数组
     */
    public static int[] findAllSlashIndices(String url) {
        if (url == null || url.isEmpty()) {
            return new int[0];
        }

        final int len = url.length();
        // 预分配最大可能结果，避免动态扩容
        int[] temp = new int[len];
        int count = 0;

        for (int i = 0; i < len; i++) {
            if (url.charAt(i) == '/') {
                temp[count++] = i;
            }
        }

        if (count == 0) {
            return new int[0];
        }

        // 拷贝为紧凑数组
        int[] result = new int[count];
        System.arraycopy(temp, 0, result, 0, count);
        return result;
    }
}
