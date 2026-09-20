package io.springperf.web.core.resource;

import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.invoker.CustomInvoker;
import io.springperf.web.core.mapping.match.HttpMethodMatcher;
import io.springperf.web.core.mapping.match.Matcher;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
public class ResourceRequestHandler implements CustomInvoker {

    public static final Method HANDLE_METHOD = ReflectionUtils.findMethod(ResourceRequestHandler.class, "handleResourceRequest", WebServerHttpRequest.class, WebServerHttpResponse.class);

    private final ResourceHandlerRegistration registration;

    /** 实际注册的 pattern（已应用 spring.mvc.static-path-pattern 全局前缀），启动期由 {@code buildPathMappingContext} 回写。 */
    private volatile String[] mappedPatterns;

    public ResourceHandlerRegistration getRegistration() {
        return registration;
    }

    void updateMappedPatterns(List<String> effectivePatterns) {
        this.mappedPatterns = effectivePatterns.toArray(new String[0]);
    }

    /**
     * 从请求路径剥离映射 pattern 的静态前缀，得到资源相对路径。
     * 对齐 Spring {@code ResourceHttpRequestHandler}：pattern {@code /res/**} 下请求
     * {@code /res/a.txt} 应在资源位置下查找 {@code a.txt}（而非 {@code res/a.txt}）。
     * 默认 pattern {@code /**} 前缀为空，路径原样使用。
     */
    private String stripMappedPrefix(String path) {
        String[] patterns = mappedPatterns;
        if (patterns == null) {
            return path;
        }
        for (String pattern : patterns) {
            if (pattern.endsWith("/**")) {
                String prefix = pattern.substring(0, pattern.length() - 3);
                if (prefix.isEmpty() || "/".equals(prefix)) {
                    return path;
                }
                if (path.equals(prefix)) {
                    return "/";
                }
                if (path.startsWith(prefix + "/")) {
                    return path.substring(prefix.length());
                }
            }
        }
        return path;
    }

    private final ConcurrentMap<String, Resource> resourceCache = new ConcurrentHashMap<>();
    /** gzip 探测结果缓存（path → gzip 资源或 NO_GZIP 哨兵），避免每请求对 {@code .gz} 做存在性探测。 */
    private final ConcurrentMap<String, Resource> gzipResourceCache = new ConcurrentHashMap<>();
    private static final Resource NO_GZIP = new ClassPathResource("__no_gzip_marker__");

    public ResourceRequestHandler(ResourceHandlerRegistration registration) {
        this.registration = registration;
    }

    @Override
    public Object invoke(Object[] args) throws Throwable {
        WebServerHttpRequest req = (WebServerHttpRequest) args[0];
        WebServerHttpResponse resp = (WebServerHttpResponse) args[1];
        handleResourceRequest(req, resp);
        return null;
    }

    public void handleResourceRequest(WebServerHttpRequest req, WebServerHttpResponse resp) {
        String path = req.getPath();
        String resourcePath = reformatPath(stripMappedPrefix(path));

        if (resourcePath == null || resourcePath.contains("..")) {
            resp.sendError(HttpStatus.NOT_FOUND);
            return;
        }

        Resource resource = getResource(resourcePath);

        // try welcome page for directory-like paths
        if (resource == null && isDirectoryPath(resourcePath)) {
            String indexPath = resourcePath + (resourcePath.endsWith("/") ? "" : "/") + "index.html";
            resource = getResource(indexPath);
        }

        if (resource == null) {
            resp.sendError(HttpStatus.NOT_FOUND);
            return;
        }

        try {
            // try gzip pre-compressed variant
            Resource gzipResource = getGzipResource(resource, req);
            boolean useGzip = gzipResource != null;

            if (useGzip) {
                resource = gzipResource;
            }

            // 缓存指令须先于条件判定写出：304 响应按 RFC 9110 §15.4.5 应携带与 200 相同的
            // Cache-Control（否则客户端拿到 304 却无从刷新新鲜度，只能靠启发式失效）
            applyCacheControl(resp);

            // check conditional GET (ETag / If-Modified-Since)
            if (checkNotModified(req, resp, resource)) {
                return;
            }

            // set Content-Type
            MediaType mediaType = MediaTypeFactory.getMediaType(resource)
                    .orElse(MediaType.APPLICATION_OCTET_STREAM);
            resp.getHeaders().setContentType(mediaType);

            // set Content-Length
            long contentLength = resource.contentLength();
            if (contentLength >= 0) {
                resp.getHeaders().setContentLength(contentLength);
            }

            // 宣告支持 byte range（RFC 9110 §14.3）：客户端据此可发起断点续传 / 视频拖动
            resp.getHeaders().add(HttpHeaders.ACCEPT_RANGES, "bytes");

            // byte range（RFC 9110 §14.1）：仅对未压缩实体生效。预压缩变体（.gz）是另一份
            // 字节流，对其切片会产生不可解码的 gzip 片段，因此回退整实体。
            if (!useGzip && contentLength > 0 && ifRangeAllows(req, resource)) {
                List<HttpRange> ranges;
                try {
                    ranges = HttpRange.parseRanges(req.getHeaders().getFirst(HttpHeaders.RANGE));
                } catch (IllegalArgumentException ex) {
                    // 语法错误的 Range：按 RFC 忽略该头，返回整实体
                    ranges = java.util.Collections.emptyList();
                }
                if (ranges.size() == 1) {
                    HttpRange range = ranges.get(0);
                    long start = range.getRangeStart(contentLength);
                    long end = range.getRangeEnd(contentLength);
                    if (start > end || start >= contentLength) {
                        // 不可满足：416 + Content-Range: bytes */len
                        resp.getHeaders().set(HttpHeaders.CONTENT_RANGE, "bytes */" + contentLength);
                        resp.sendError(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
                        return;
                    }
                    long rangeLength = end - start + 1;
                    resp.getHeaders().set(HttpHeaders.CONTENT_RANGE,
                            "bytes " + start + "-" + end + "/" + contentLength);
                    // 206 的 Content-Length 必须是区间长度（HEAD 与 GET 同头，故此处显式改写）
                    resp.getHeaders().setContentLength(rangeLength);
                    resp.setStatusCode(HttpStatus.PARTIAL_CONTENT);
                    if (req.isHeadRequest()) {
                        // HEAD：只回元数据（Content-Range/Content-Length 已设置），不读文件
                        resp.setHandled();
                        return;
                    }
                    resp.writeStream(new RangeInputStream(resource.getInputStream(), start, rangeLength),
                            rangeLength);
                    return;
                }
                if (ranges.size() > 1) {
                    int maxRanges = maxRanges(req);
                    if (!exceedsMaxRanges(ranges.size(), maxRanges)) {
                        // 多段 range：multipart/byteranges（RFC 9110 §14.4；对齐 Spring ResourceHttpRequestHandler / Tomcat）
                        serveMultipartByteranges(req, resp, resource, ranges, contentLength, mediaType);
                        return;
                    }
                    // 段数超限：按 RFC 9110 §14.2 忽略 Range 返回整实体——不截断段数（截断会让客户端
                    // 拿到与请求不符的表示），也不回 416（请求本身合法）。防「单请求放大成多路区间流」。
                    log.debug("Range ignored: {} segments exceed {}={}",
                            ranges.size(), PropertiesConstant.HTTP_MAX_RANGES, maxRanges);
                }
                // ranges 为空（头缺失或语法错误）：回退整实体
            }


            // set Content-Encoding for gzip
            if (useGzip) {
                resp.getHeaders().add(HttpHeaders.CONTENT_ENCODING, "gzip");
                // 预压缩变体是按 Accept-Encoding 协商选出的另一份表示，必须声明 Vary，
                // 否则共享缓存会把 gzip 表示回给未声明该编码的客户端（经典缓存污染）
                if (resp.getHeaders().get(HttpHeaders.VARY) == null) {
                    resp.getHeaders().add(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING);
                } else if (!resp.getHeaders().get(HttpHeaders.VARY).contains(HttpHeaders.ACCEPT_ENCODING)) {
                    resp.getHeaders().add(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING);
                }
            }

            // write resource
            resp.setStatusCode(HttpStatus.OK);
            // HEAD：仅返回元数据（Content-Length/ETag/Last-Modified 已在上面设置），
            // 跳过 body 读取——避免打开文件流，资源探测零 IO
            if (!req.isHeadRequest()) {
                // 长度已知时用 Content-Length 帧：chunked 会让上面设置的 Content-Length 被
                // Netty 静默移除，客户端拿不到总长度（无法显示下载进度/预知大小）
                if (contentLength >= 0) {
                    resp.writeStream(resource.getInputStream(), contentLength);
                } else {
                    resp.writeStream(resource.getInputStream());
                }
            } else {
                resp.setHandled();
            }
        } catch (Exception e) {
            log.error("Failed to serve resource: {}", resourcePath, e);
            resp.sendError(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 多段 Range 段数上限（{@code server.http.max-ranges}，有效上限 = min(本值, 100)，见
     * {@link PropertiesConstant#HTTP_MAX_RANGES}）。
     *
     * <p>**只在多段请求分支读取**：无 Range / 单段请求（绝大多数）不触碰配置，热路径零成本；
     * 每次读取可自然跟随配置刷新。无 WebContext（单测替身）或取值非法时回退默认上限（不放大防护）。</p>
     */
    private static int maxRanges(WebServerHttpRequest req) {
        try {
            WebContext ctx = req.getWebContext();
            if (ctx == null || ctx.getProps() == null) {
                return PropertiesConstant.HTTP_MAX_RANGES_DEFAULT;
            }
            String raw = ctx.getProps().get(PropertiesConstant.HTTP_MAX_RANGES, null);
            if (raw == null || raw.trim().isEmpty()) {
                return PropertiesConstant.HTTP_MAX_RANGES_DEFAULT;
            }
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            // 非法值（非整数）按默认上限处理，不因配置错误放大防护
            return PropertiesConstant.HTTP_MAX_RANGES_DEFAULT;
        }
    }

    /**
     * 段数是否超过上限：上限为负表示不限制；{@code 0} 表示禁止多段（任何多段请求都回退整实体）。
     * 独立成包级方法以便单测直接锁定边界（0 / 恰好等于上限 / 超一）。
     */
    static boolean exceedsMaxRanges(int segments, int maxRanges) {
        return maxRanges >= 0 && segments > maxRanges;
    }

    /**
     * 多段 Range：multipart/byteranges（RFC 9110 §14.4；对齐 Spring ResourceHttpRequestHandler / Tomcat）。
     *
     * <p>每段自带 {@code Content-Type} 与 {@code Content-Range}，段间以 CRLF 分隔，末尾
     * {@code --boundary--} 收尾；总 Content-Length 写出前逐段精确累加（分段头是 ASCII 定长的，
     * 可安全预先计算）。任一段不可满足（start &gt;= 实体长度）→ 整体 416 +
     * {@code Content-Range: bytes *&#47;len}（对齐 Spring：任一 region 越界即整体不可满足）。</p>
     */
    private void serveMultipartByteranges(WebServerHttpRequest req, WebServerHttpResponse resp,
                                          Resource resource, List<HttpRange> ranges,
                                          long contentLength, MediaType mediaType) throws IOException {
        for (HttpRange range : ranges) {
            if (range.getRangeStart(contentLength) >= contentLength) {
                resp.getHeaders().set(HttpHeaders.CONTENT_RANGE, "bytes */" + contentLength);
                resp.sendError(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
                return;
            }
        }
        String boundary = "springperf-" + UUID.randomUUID();
        List<byte[]> prefixes = new ArrayList<>(ranges.size());
        List<long[]> segments = new ArrayList<>(ranges.size());
        long total = 0;
        for (HttpRange range : ranges) {
            long start = range.getRangeStart(contentLength);
            long end = range.getRangeEnd(contentLength);
            byte[] prefix = ("--" + boundary + "\r\n"
                    + "Content-Type: " + mediaType + "\r\n"
                    + "Content-Range: bytes " + start + "-" + end + "/" + contentLength + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
            prefixes.add(prefix);
            segments.add(new long[]{start, end});
            // 段前缀 + 区间字节 + 段后 CRLF
            total += prefix.length + (end - start + 1) + 2;
        }
        byte[] closing = ("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        total += closing.length;

        resp.getHeaders().setContentType(
                MediaType.parseMediaType("multipart/byteranges; boundary=" + boundary));
        // 206 的 Content-Length 必须是多段体总长（HEAD 与 GET 同头，故此处显式设置）
        resp.getHeaders().setContentLength(total);
        resp.setStatusCode(HttpStatus.PARTIAL_CONTENT);
        if (req.isHeadRequest()) {
            // HEAD：只回元数据，不读文件
            resp.setHandled();
            return;
        }
        resp.writeStream(multipartStream(resource, prefixes, segments, closing), total);
    }

    /** 顺序拼接：每段「段头 + 区间字节 + CRLF」，最后以 {@code --boundary--} 收尾。 */
    private static InputStream multipartStream(Resource resource, List<byte[]> prefixes,
                                               List<long[]> segments, byte[] closing) throws IOException {
        List<InputStream> parts = new ArrayList<>(segments.size() * 3 + 1);
        for (int i = 0; i < segments.size(); i++) {
            parts.add(new ByteArrayInputStream(prefixes.get(i)));
            long[] seg = segments.get(i);
            parts.add(new RangeInputStream(resource.getInputStream(), seg[0], seg[1] - seg[0] + 1));
            parts.add(new ByteArrayInputStream(new byte[]{'\r', '\n'}));
        }
        parts.add(new ByteArrayInputStream(closing));
        return new SequenceInputStream(java.util.Collections.enumeration(parts));
    }

    /**
     * {@code If-Range} 判定（RFC 9110 §13.1.5）：未携带该头 → 允许 Range；
     * 携带实体标签 → 与当前 ETag 精确匹配才允许；携带 HTTP 日期 → 资源未在该时刻后修改才允许。
     * 不匹配时必须忽略 Range 返回整实体（否则客户端会拿到与新版实体不一致的片段）。
     */
    private static boolean ifRangeAllows(WebServerHttpRequest req, Resource resource) {
        String ifRange = req.getHeaders().getFirst(HttpHeaders.IF_RANGE);
        if (ifRange == null || ifRange.trim().isEmpty()) {
            return true;
        }
        String value = ifRange.trim();
        try {
            if (value.startsWith("\"") || value.startsWith("W/")) {
                return value.equals(computeEtag(resource));
            }
            long ifRangeDate = java.time.ZonedDateTime
                    .parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
            // 与 If-Modified-Since 同口径：资源时间按秒截断后比较
            return (resource.lastModified() / 1000 * 1000) <= ifRangeDate;
        } catch (Exception e) {
            log.debug("Failed to evaluate If-Range: {}", value, e);
            return false;
        }
    }

    /**
     * 限定区间的输入流：先跳过 {@code start} 字节，再最多读取 {@code length} 字节。
     * 用于 206 分片响应（避免把整文件读入内存）。
     */
    static final class RangeInputStream extends java.io.FilterInputStream {

        private long remaining;
        private long toSkip;

        RangeInputStream(java.io.InputStream in, long start, long length) {
            super(in);
            this.toSkip = start;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (!skipFully()) {
                return -1;
            }
            if (remaining <= 0) {
                return -1;
            }
            int b = super.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (!skipFully()) {
                return -1;
            }
            if (remaining <= 0) {
                return -1;
            }
            int toRead = (int) Math.min(len, remaining);
            int n = super.read(b, off, toRead);
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            if (toSkip > 0) {
                return super.skip(n);
            }
            long skipped = super.skip(Math.min(n, remaining));
            remaining -= skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            long skipped = toSkip > 0 ? 0 : remaining;
            return (int) Math.min(super.available(), skipped);
        }

        private boolean skipFully() throws IOException {
            while (toSkip > 0) {
                long skipped = super.skip(toSkip);
                if (skipped <= 0) {
                    if (super.read() < 0) {
                        return false;
                    }
                    skipped = 1;
                }
                toSkip -= skipped;
            }
            return true;
        }
    }

    private static boolean isDirectoryPath(String path) {
        if (path.isEmpty() || path.endsWith("/")) {
            return true;
        }
        int lastSep = path.lastIndexOf('/');
        String lastSegment = lastSep >= 0 ? path.substring(lastSep + 1) : path;
        return lastSegment.indexOf('.') < 0;
    }

    @Nullable
    private Resource getGzipResource(Resource resource, WebServerHttpRequest req) {
        List<String> acceptEncodings = req.getHeaders().get(HttpHeaders.ACCEPT_ENCODING);
        if (acceptEncodings == null || acceptEncodings.isEmpty()) {
            return null;
        }
        boolean acceptsGzip = false;
        for (String encoding : acceptEncodings) {
            if (encoding.contains("gzip")) {
                acceptsGzip = true;
                break;
            }
        }
        if (!acceptsGzip) {
            return null;
        }
        try {
            String uri = resource.getURI().toString();
            // 缓存 gzip 探测结果（含 NO_GZIP 哨兵），避免每请求对 .gz 做存在性检查
            Resource cached = gzipResourceCache.get(uri);
            if (cached != null) {
                return cached == NO_GZIP ? null : cached;
            }
            Resource gzipResource = resolveResourceByUri(uri + ".gz");
            gzipResourceCache.put(uri, gzipResource != null ? gzipResource : NO_GZIP);
            if (gzipResource != null && gzipResource.exists() && gzipResource.isReadable()) {
                return gzipResource;
            }
        } catch (Exception e) {
            log.debug("Failed to resolve gzip resource for: {}", resource.getDescription(), e);
        }
        return null;
    }

    /** 写出 Cache-Control（显式 CacheControl 优先于 cache.period 推导）。 */
    private void applyCacheControl(WebServerHttpResponse resp) {
        if (registration.getCacheControl() != null) {
            resp.getHeaders().setCacheControl(registration.getCacheControl().getHeaderValue());
        } else if (registration.getCachePeriod() != null) {
            long period = registration.getCachePeriod();
            if (period > 0) {
                resp.getHeaders().setCacheControl("max-age=" + period + ", must-revalidate");
            } else if (period == 0) {
                resp.getHeaders().setCacheControl("no-cache, no-store, must-revalidate");
            }
        }
    }

    /**
     * Check if the resource has been modified since the last request.
     * Handles If-Modified-Since and If-None-Match (ETag).
     * @return true if a 304 response has been set, false otherwise
     */
    private boolean checkNotModified(WebServerHttpRequest req, WebServerHttpResponse resp, Resource resource) {
        try {
            long lastModified = resource.lastModified();
            if (lastModified < 0) {
                return false;
            }
            String eTag = computeEtag(resource);

            // set Last-Modified and ETag on response
            resp.getHeaders().setLastModified(lastModified);
            resp.getHeaders().setETag(eTag);

            // If-None-Match 优先，且**存在时不得再评估 If-Modified-Since**（RFC 9110 §13.1.3）
            List<String> ifNoneMatch = req.getHeaders().getIfNoneMatch();
            if (ifNoneMatch != null && !ifNoneMatch.isEmpty()) {
                for (String clientEtag : ifNoneMatch) {
                    // 弱比较（RFC 9110 §13.1.2）：If-None-Match 忽略 W/ 弱前缀，
                    // 否则 CDN/代理回传弱化 ETag 时会持续 200，缓存命中率归零
                    if ("*".equals(clientEtag) || weakEquals(clientEtag, eTag)) {
                        resp.setStatusCode(HttpStatus.NOT_MODIFIED);
                        return true;
                    }
                }
                return false;
            }

            // check If-Modified-Since
            long ifModifiedSince = req.getHeaders().getIfModifiedSince();
            if (ifModifiedSince >= 0 && (lastModified / 1000 * 1000) <= ifModifiedSince) {
                resp.setStatusCode(HttpStatus.NOT_MODIFIED);
                return true;
            }
        } catch (Exception e) {
            log.warn("Failed to check not modified for resource", e);
        }
        return false;
    }

    /** 弱比较：忽略 {@code W/} 弱前缀后比较 opaque-tag（RFC 9110 §8.8.3.2）。 */
    private static boolean weakEquals(String candidate, String etag) {
        return normalizeEtag(candidate).equals(normalizeEtag(etag));
    }

    private static String normalizeEtag(String tag) {
        String t = tag.trim();
        if (t.length() > 2 && (t.startsWith("W/") || t.startsWith("w/"))) {
            return t.substring(2).trim();
        }
        return t;
    }

    private static String computeEtag(Resource resource) throws IOException {
        long lastModified = resource.lastModified();
        long contentLength = resource.contentLength();
        return "\"0x" + Long.toHexString(lastModified) + "-" + contentLength + "\"";
    }

    protected String reformatPath(String path) {
        for (String pattern : registration.getPathPatterns()) {
            String prefix = pattern.replace("/**", "").replace("/*", "");
            if (path.startsWith(prefix)) {
                String relativePath = path.substring(prefix.length());
                if (!relativePath.startsWith("/")) {
                    relativePath = "/" + relativePath;
                }
                // prevent directory traversal (check before cleanPath resolves the .. segments)
                if (relativePath.contains("..")) {
                    return null;
                }
                return StringUtils.cleanPath(relativePath);
            }
        }
        return path;
    }

    @Nullable
    protected Resource getResource(String path) {
        // 缓存命中直接返回：resolveResource 在入缓存前已校验 exists/isReadable，
        // 命中后再调 cached.exists() 是每请求的磁盘 stat（FileSystemResource → File.exists syscall）。
        Resource cached = resourceCache.get(path);
        if (cached != null) {
            return cached;
        }

        for (String location : registration.getLocationValues()) {
            Resource resource = resolveResource(location, path);
            if (resource != null && resource.exists() && resource.isReadable()) {
                resourceCache.put(path, resource);
                return resource;
            }
        }
        return null;
    }

    @Nullable
    private Resource resolveResource(String location, String path) {
        String fullPath = location + path;
        try {
            if (location.startsWith(ResourceUtils.CLASSPATH_URL_PREFIX)) {
                String classpathLocation = fullPath.substring(ResourceUtils.CLASSPATH_URL_PREFIX.length());
                ClassPathResource resource = new ClassPathResource(classpathLocation);
                if (resource.exists() && resource.isReadable()) {
                    return resource;
                }
            } else if (location.startsWith(ResourceUtils.FILE_URL_PREFIX)) {
                String filePath = fullPath.substring(ResourceUtils.FILE_URL_PREFIX.length());
                File file = new File(filePath);
                if (file.exists() && file.isFile()) {
                    return new FileSystemResource(file);
                }
            } else {
                File file = new File(fullPath);
                if (file.exists() && file.isFile()) {
                    return new FileSystemResource(file);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve resource at {}", fullPath, e);
        }
        return null;
    }

    @Nullable
    private Resource resolveResourceByUri(String uri) {
        try {
            if (uri.startsWith("file:")) {
                File file = new File(uri.substring(5));
                if (file.exists() && file.isFile()) {
                    return new FileSystemResource(file);
                }
            } else if (uri.startsWith("classpath:")) {
                ClassPathResource resource = new ClassPathResource(uri.substring("classpath:".length()));
                if (resource.exists() && resource.isReadable()) {
                    return resource;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve resource by uri: {}", uri, e);
        }
        return null;
    }

    /**
     * Clear the internal resource and gzip-probe caches.
     */
    public void clearCache() {
        resourceCache.clear();
        gzipResourceCache.clear();
    }

    public Method getHandleMethod() {
        return HANDLE_METHOD;
    }

    public List<Matcher> getMatchers() {
        // 仅允许 GET 访问静态资源（对齐 Spring MVC ResourceHttpRequestHandler 语义）；
        // HEAD 无需显式声明——HttpMethodMatcher 在路由时自动将 HEAD 映射到 GET（RFC 7231 §4.3.2）
        Matcher matcher = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET});
        return Arrays.asList(matcher);
    }

    @Override
    public String getType() {
        return "Resource";
    }
}
