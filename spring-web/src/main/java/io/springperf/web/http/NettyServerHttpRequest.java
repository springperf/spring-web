package io.springperf.web.http;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.lang.Nullable;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.MultiValueMapAdapter;
import org.springframework.web.multipart.MultipartFile;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.http.support.HttpInputMessagePart;
import io.springperf.web.http.support.NettyAttributeMessage;
import io.springperf.web.http.support.NettyMultipartWebRequest;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NettyServerHttpRequest extends BaseWebServerHttpRequest {

    private final ChannelHandlerContext ctx;
    private final FullHttpRequest request;
    private final int largeBodyLimit;
    /** 参数值总数上限（来自 {@code server.max-parameter-count}，≤0 表示不限制）。 */
    private final int maxParameterCount;
    private HttpHeaders headers;
    private URI uri;

    /** {@link #getMethod()} 解析结果缓存（见该方法说明；幂等，允许并发重复计算一次）。 */
    private HttpMethod method;
    private static final byte[] EMPTY_BODY = new byte[0];
    private static final String FORM_URLENCODED = HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED.toString();

    private volatile byte[] body;
    private ByteBuf largeBodyBuf;

    /**
     * 请求体长度（构造期缓存）。
     * <p>
     * 不在 {@link #getContentLength()} 里现读 {@code request.content().readableBytes()}：入站 buf 在同步收尾后即被释放（见
     * {@link #release()} 与 NettyHttpHandler 引用计数说明），异步阶段再触碰 {@code content()} 会读到陈旧长度、或（未被复用时）抛
     * {@code IllegalReferenceCountException}。 聚合后的 body 长度在构造时即已确定，缓存无信息损失。
     * </p>
     */
    private final int contentLength;

    /** 原始 URI（含查询串；与基类持有的是同一对象，零额外分配）。 */
    private final String uriWithQuery;

    /** 查询串起始下标（{@code '?'} 之后）；无查询串为 {@code -1}。只记下标，不截取子串。 */
    private final int queryStart;

    /**
     * 快路径资格<b>懒判定</b>状态：{@code 0}=未判定、{@code 1}=可用、{@code 2}=不可用。
     * <p>
     * 懒判定的两点理由：① 无 {@code @RequestParam} 的请求完全不必扫查询串； ② 判定需单趟扫描（转义/分隔符 + 段数），把它从构造期移到首次取值，避免构造期白扫。
     * </p>
     */
    private volatile byte fastQueryState = FAST_UNKNOWN;
    private static final byte FAST_UNKNOWN = 0;
    private static final byte FAST_YES = 1;
    private static final byte FAST_NO = 2;

    /** 快路径扫描段数上限：超过则回退通用路径（避免 O(段数×参数数) 反超哈希查找）。 */
    private static final int FAST_QUERY_MAX_SEGMENTS = 8;

    /** 绑定的响应对象：请求释放时级联兜底释放其未提交 buf（生命周期绑定，详见 {@link #release()}） */
    private WebServerHttpResponse response;

    /**
     * 绑定其响应对象。仅在 NettyHttpHandler 构造 request 后调用一次， 使响应未提交 buf 随请求释放而兜底释放。
     */
    public void setResponse(WebServerHttpResponse response) {
        this.response = response;
    }

    /**
     * 使用已解析的 path 构造，跳过 context-path 校验。
     * <p>
     * 供管理端口使用——请求 URI 不包含 context-path 前缀时直接使用原始路径。
     * </p>
     */
    public NettyServerHttpRequest(WebContext webContext, ChannelHandlerContext ctx, FullHttpRequest request,
            String resolvedPath) {
        super(webContext, request.uri(), resolvedPath);
        this.ctx = ctx;
        this.request = request;
        // 读取体内联阈值与参数上限：均为 ApplicationProperties 热路径字段（纯字段读，
        // 构造期已急切解析、clearCache 自动刷新；实例级隔离主/管理端口）
        this.largeBodyLimit = webContext.getProps().getMaxInMemorySize();
        this.maxParameterCount = webContext.getProps().getMaxParameterCount();
        // body 长度在聚合完成时即确定，构造期一次读出，后续不再触碰 content()
        this.contentLength = request.content().readableBytes();
        // 查询串位置：只记 '?' 下标（零分配、零扫描），资格判定推迟到首次取值（见 fastQueryEligible）
        this.uriWithQuery = getUriStrWithQuery();
        int queryIndex = this.uriWithQuery.indexOf('?');
        this.queryStart = queryIndex < 0 ? -1 : queryIndex + 1;
    }

    public FullHttpRequest getNativeRequest() {
        return request;
    }

    protected MultiValueMap<String, String> parseParameters() {
        QueryStringDecoder queryStringDecoder = new QueryStringDecoder(getUriStrWithQuery());
        Map<String, List<String>> params = queryStringDecoder.parameters();
        if (request instanceof NettyMultipartWebRequest) {
            NettyMultipartWebRequest multipartWebRequest = (NettyMultipartWebRequest) request;
            MultiValueMap<String, NettyAttributeMessage> attributeMessageMap = multipartWebRequest.getParameters();
            if (!attributeMessageMap.isEmpty()) {
                MultiValueMap<String, String> result = new LinkedMultiValueMap<>();
                for (Map.Entry<String, List<NettyAttributeMessage>> entry : attributeMessageMap.entrySet()) {
                    for (NettyAttributeMessage attr : entry.getValue()) {
                        try {
                            result.add(entry.getKey(), attr.getValue());
                        } catch (Exception e) {
                            log.error("Failed to parse form field: {}", attr.getName(), e);
                        }
                    }
                }
                params.forEach((k, values) -> result.addAll(k, values));
                enforceParameterLimit(result);
                return result;
            }
        }
        String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
        if (contentType != null && contentType.regionMatches(true, 0, FORM_URLENCODED, 0, FORM_URLENCODED.length())) {
            // 表单参数按请求 characterEncoding 手工解码（对齐 Servlet 规范：容器
            // server.servlet.encoding.force-request 设置的编码必须作用于表单解码）。
            // 不用 Netty HttpPostStandardRequestDecoder——其 urlencoded 分支忽略构造器
            // charset、固定按 Content-Type（缺省 UTF-8）解码，容器编码无法生效。
            byte[] bodyBytes = new byte[request.content().readableBytes()];
            request.content().getBytes(request.content().readerIndex(), bodyBytes);
            String form = new String(bodyBytes, getCharacterEncoding());
            if (!form.trim().isEmpty()) {
                MultiValueMap<String, String> result = new LinkedMultiValueMap<>();
                for (String pair : form.split("&")) {
                    if (pair.isEmpty()) {
                        continue;
                    }
                    int eq = pair.indexOf('=');
                    String name = eq >= 0 ? pair.substring(0, eq) : pair;
                    String value = eq >= 0 ? pair.substring(eq + 1) : "";
                    result.add(java.net.URLDecoder.decode(name, getCharacterEncoding()),
                            java.net.URLDecoder.decode(value, getCharacterEncoding()));
                }
                params.forEach((k, values) -> result.addAll(k, values));
                enforceParameterLimit(result);
                return result;
            }
        }
        MultiValueMap<String, String> fallback = new MultiValueMapAdapter<>(params);
        enforceParameterLimit(fallback);
        return fallback;
    }

    /**
     * 校验参数值总数是否超出 {@code server.max-parameter-count}（hash DoS 防护）。 超限抛出 {@link ParameterLimitExceededException}，由
     * NettyHttpHandler 捕获转 400。
     */
    private void enforceParameterLimit(MultiValueMap<String, String> params) {
        if (maxParameterCount > 0 && params.size() > maxParameterCount) {
            throw new ParameterLimitExceededException(maxParameterCount, params.size());
        }
    }

    /**
     * {@code @RequestParam} 等单值命名的快路径：直接在原始查询串上线性扫描。
     * <p>
     * 通用路径（{@link #getParameterMap()} → {@code QueryStringDecoder} → {@code LinkedHashMap} →
     * {@code MultiValueMapAdapter}）每请求都要：为每个参数名解析/建 Map（含 String 哈希）、 为每次名字查找做一次哈希桶定位。JFR
     * 采样（{@code PerfBenchmark.get}，5 个 {@code @RequestParam}） 显示这两项合计约占该端点 CPU 的 5%（解码 60 样本 + 名字查找 78/24 样本，总 3205）。
     * 本方法在「无 body 的纯查询请求 + 查询串无 {@code % + ; #}」（{@link #fastQueryEligible}）时 直扫查询串：<b>零哈希、零 Map/List 分配</b>，仅命中时创建一个
     * value 子串。
     * <p>
     * 语义与通用路径严格一致：
     * <ul>
     * <li>同名多值返回第一个（对齐 {@code MultiValueMap#getFirst}）；</li>
     * <li>缺 {@code =} 时值为空串（对齐 Netty 解码）；</li>
     * <li>未命中返回 {@code null}：快路径仅在「参数只可能来自查询串」时启用，故不存在遗漏来源；</li>
     * <li>段数超过 {@link #FAST_QUERY_MAX_SEGMENTS} 或超过 {@code server.max-parameter-count} （hash DoS
     * 防护）时，{@link #fastQueryEligible()} 判定为不可用 → 一律走通用路径， 由后者抛原有 400，<b>不可被命中早退绕过</b>（资格判定先于任何命中，见回归用例
     * {@code parameterLimitExceeded_stillThrows}）；</li>
     * <li>含转义 / 分号 / 片段时同样不走快路径。</li>
     * </ul>
     */
    @Override
    public String getParameter(String name) {
        if (name == null || name.isEmpty()) {
            return super.getParameter(name);
        }
        if (queryStart < 0) {
            // 无查询串：参数只可能来自 body / multipart；两者都不适用时直接判定「无此参数」，
            // 连通用路径（建解码器 + 空 Map）都省掉。
            if (contentLength == 0 && !(request instanceof NettyMultipartWebRequest)) {
                return null;
            }
            return super.getParameter(name);
        }
        if (fastQueryEligible()) {
            return fastQueryGet(name);
        }
        return super.getParameter(name);
    }

    /**
     * 快路径资格（懒判定，单趟扫描）：无 body（参数只可能来自查询串）、非 multipart、 查询串不含 {@code % + ; #}，且段数 ≤ min({@link #FAST_QUERY_MAX_SEGMENTS},
     * {@code server.max-parameter-count})。
     * <p>
     * 此前该判定是构造期 4 次 {@code indexOf}（转义/分隔符）+ 1 次段数扫描共 <b>5 趟</b>， JFR 显示仅段数统计一项就占 73 样本 / 3411（≈2%，叶帧
     * {@code countQuerySegments}）。 现合并为 1 趟并懒执行：无参数取值的请求完全不扫。
     * </p>
     */
    private boolean fastQueryEligible() {
        byte state = fastQueryState;
        if (state == FAST_UNKNOWN) {
            state = computeFastQueryEligibility() ? FAST_YES : FAST_NO;
            fastQueryState = state;
        }
        return state == FAST_YES;
    }

    private boolean computeFastQueryEligibility() {
        if (contentLength != 0 || request instanceof NettyMultipartWebRequest) {
            return false;
        }
        String uri = uriWithQuery;
        int n = uri.length();
        int segments = 1;
        for (int i = queryStart; i < n; i++) {
            char c = uri.charAt(i);
            if (c == '&') {
                segments++;
            } else if (c == '%' || c == '+' || c == ';' || c == '#') {
                return false;
            }
        }
        int cap = (maxParameterCount > 0 && maxParameterCount < FAST_QUERY_MAX_SEGMENTS) ? maxParameterCount
                : FAST_QUERY_MAX_SEGMENTS;
        return segments <= cap;
    }

    /** 在原始 URI 上按段扫描取首个同名值（下标基于 {@link #queryStart}，不截取查询串子串）。 */
    private String fastQueryGet(String name) {
        String uri = uriWithQuery;
        int n = uri.length();
        int nameLen = name.length();
        int i = queryStart;
        while (true) {
            int amp = uri.indexOf('&', i);
            int end = amp < 0 ? n : amp;
            int eq = uri.indexOf('=', i);
            int keyEnd = (eq < 0 || eq >= end) ? end : eq;
            if (keyEnd - i == nameLen && uri.regionMatches(i, name, 0, nameLen)) {
                return keyEnd == end ? "" : uri.substring(keyEnd + 1, end);
            }
            if (amp < 0) {
                return null;
            }
            i = amp + 1;
        }
    }

    @Override
    public MultiValueMap<String, MultipartFile> getMultiFileMap() {
        if (request instanceof NettyMultipartWebRequest) {
            return ((NettyMultipartWebRequest) request).getFiles();
        }
        return null;
    }

    @Override
    public MultiValueMap<String, HttpInputMessagePart> getPartMap() {
        if (request instanceof NettyMultipartWebRequest) {
            return ((NettyMultipartWebRequest) request).getParts();
        }
        return null;
    }

    @Override
    public HttpMethod getMethod() {
        // 缓存：每请求会被多处读取（HttpMethodMatcher.match 路由 + CorsUtils 预检判定），
        // 原实现每次 HttpMethod.valueOf(String) 都要做一次名字哈希+表查找
        // （JFR 服务端线程：14 样本/1483）。幂等，无需同步——并发读到 null 时各算一次即可。
        HttpMethod m = method;
        if (m == null) {
            m = HttpMethod.valueOf(request.method().name());
            method = m;
        }
        return m;
    }

    @Override
    public String getMethodValue() {
        return request.method().name();
    }

    @Override
    public HttpHeaders getHeaders() {
        if (headers == null) {
            // 零拷贝可写视图：直接委托 Netty headers，免去 O(n) 拷贝；
            // 对齐 Spring ServerHttpRequest.getHeaders() 契约（可写），写操作穿透到 Netty 请求对象；
            // Netty 大小写不敏感解析同时修复了旧拷贝下小写 key 读取 miss 的问题。
            headers = new WebHttpHeaders(new NettyHttpHeadersAdapter(request.headers(), true));
        }
        return headers;
    }

    @Override
    public URI getURI() {
        if (uri == null) {
            String scheme = resolveScheme();
            uri = URI.create(scheme + "://" + resolveAuthority() + getUriStrWithQuery());
        }
        return uri;
    }

    /**
     * 构造 request URI 的 authority（host[:port]）。
     * <p>
     * 优先级：
     * <ol>
     * <li>信任转发头时，取自 {@code X-Forwarded-Host} / RFC 7239 {@code Forwarded} 的 {@code host=}；
     * 若该值未自带端口，再叠加 {@code X-Forwarded-Port}</li>
     * <li>{@code Host} 请求头</li>
     * <li>本地监听地址（通道未绑定或 Host 缺失时的兜底）</li>
     * </ol>
     * </p>
     */
    private String resolveAuthority() {
        String scheme = resolveScheme();
        String forwardedHost = resolveForwardedHost();
        // host 与 port 独立取值：代理可能只转发其一（例如只给 X-Forwarded-Port），
        // 此时 host 回退到 Host 头、port 仍应采用转发值。
        String host = forwardedHost;
        Integer port = null;
        if (host == null) {
            String hostHeader = request.headers().get(HttpHeaderNames.HOST);
            if (hostHeader != null) {
                // Host 头自带端口时先剥离：本方法统一在末尾补端口，避免出现 host:port:port。
                // 注意剥出的端口要保留下来，Host 是权威来源（无转发头时它就是客户端请求的主机:端口）。
                int colon = hostHeader.lastIndexOf(':');
                if (colon > hostHeader.lastIndexOf(']')) {
                    host = hostHeader.substring(0, colon);
                    try {
                        port = Integer.parseInt(hostHeader.substring(colon + 1));
                    } catch (NumberFormatException ignored) {
                        // Host 头端口非法：视为无端口
                    }
                } else {
                    host = hostHeader;
                }
            }
        }
        if (forwardedHost != null && hostHasPort(forwardedHost)) {
            // 转发 host 自带端口（如 public.example.com:8443）最优先
            int colon = forwardedHost.lastIndexOf(':');
            try {
                port = Integer.parseInt(forwardedHost.substring(colon + 1));
            } catch (NumberFormatException ignored) {
                // 解析不出则忽略，走下面的 forwarded port
            }
            host = forwardedHost.substring(0, colon);
        }
        if (forwardedPortIndicated()) {
            // X-Forwarded-Port 是代理明确给出的外部端口，优先于 Host 头里的内网端口
            // （Host 可能形如 internal-app:8080，而外部实际是 443）
            port = resolveForwardedPort();
        }
        if (host == null) {
            // getLocalAddress 声明为 @Nullable（通道未绑定本地地址时为 null）：退化为不带端口的
            // localhost，避免构造 URI 时 NPE
            InetSocketAddress addr = getLocalAddress();
            if (addr == null) {
                return "localhost";
            }
            return addr.getHostString() + ":" + addr.getPort();
        }
        if (port == null) {
            // Host 头没给端口、也无转发端口：默认端口不出现在 authority 里（由 scheme 隐含）
            return host;
        }
        return isDefaultPort(scheme, port) ? host : host + ":" + port;
    }

    /** authority 中是否已含端口（区分 IPv6 字面量 {@code [::1]} 与 {@code host:port}）。 */
    private static boolean hostHasPort(String authority) {
        int colon = authority.lastIndexOf(':');
        return colon > authority.lastIndexOf(']');
    }

    /** 请求头中是否给出了转发端口（{@code X-Forwarded-Port} 非空且可解析）。 */
    private boolean forwardedPortIndicated() {
        return resolveForwardedPort() != null;
    }

    /** 是否是 scheme 的默认端口（http=80 / https=443）——默认端口不出现在 authority 里。 */
    private static boolean isDefaultPort(String scheme, int port) {
        return ("http".equalsIgnoreCase(scheme) && port == 80) || ("https".equalsIgnoreCase(scheme) && port == 443);
    }

    /**
     * 按优先级确定请求 scheme：
     * <ol>
     * <li>RFC 7239 {@code Forwarded} 头（当 {@code server.forward-headers-strategy}=FRAMEWORK/NATIVE）</li>
     * <li>{@code X-Forwarded-Proto} 头（同上）</li>
     * <li>Netty pipeline 中存在 {@link SslHandler}（TLS 在 Java 层终结）</li>
     * <li>兜底 {@code http}</li>
     * </ol>
     * <p>
     * 转发头默认不信任（{@code server.forward-headers-strategy}=NONE），防止客户端伪造 scheme。 部署在反向代理后方时设为 FRAMEWORK（或
     * NATIVE），并确保代理已清理上游转发头。 NATIVE 与 FRAMEWORK 在本框架行为一致（无原生容器）。
     * </p>
     */
    private String resolveScheme() {
        if (isForwardedHeadersEnabled()) {
            // 1. RFC 7239 Forwarded
            String forwarded = request.headers().get("Forwarded");
            if (forwarded != null) {
                String proto = parseForwardedDirective(forwarded, "proto");
                if (proto != null) {
                    return proto;
                }
            }
            // 2. X-Forwarded-Proto
            String forwardedProto = request.headers().get("X-Forwarded-Proto");
            if (forwardedProto != null && !forwardedProto.isEmpty()) {
                return forwardedProto;
            }
        }
        // 3. SSL 在 Java 层终结
        if (ctx.pipeline().get(SslHandler.class) != null) {
            return "https";
        }
        // 4. 兜底
        return "http";
    }

    /**
     * 解析转发的 host（{@code X-Forwarded-Host} / RFC 7239 {@code Forwarded} 的 {@code host=}）。
     * <p>
     * 仅在信任转发头（{@code server.forward-headers-strategy} 非 NONE）时生效；否则返回 null，
     * 由调用方回退到 {@code Host} 头。值可能自带端口（{@code public.example.com:8443}），
     * 由调用方按需拆分。
     * </p>
     */
    @Nullable
    public String resolveForwardedHost() {
        if (!isForwardedHeadersEnabled()) {
            return null;
        }
        String forwarded = request.headers().get("Forwarded");
        if (forwarded != null) {
            String host = parseForwardedDirective(forwarded, "host");
            if (host != null) {
                return host;
            }
        }
        String xfh = request.headers().get("X-Forwarded-Host");
        // X-Forwarded-Host 可能是逗号分隔的链（client, proxy1, proxy2）：取第一段为客户端原始主机
        if (xfh != null && !xfh.isEmpty()) {
            int comma = xfh.indexOf(',');
            String first = (comma >= 0 ? xfh.substring(0, comma) : xfh).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return null;
    }

    /**
     * 解析转发的端口（{@code X-Forwarded-Port}）。仅在信任转发头时生效，否则返回 null。
     *
     * @return 解析出的端口；头缺失或非数字时返回 null
     */
    @Nullable
    public Integer resolveForwardedPort() {
        if (!isForwardedHeadersEnabled()) {
            return null;
        }
        String xfp = request.headers().get("X-Forwarded-Port");
        if (xfp == null || xfp.isEmpty()) {
            return null;
        }
        // 同 X-Forwarded-Host，可能是逗号分隔链：取第一段
        int comma = xfp.indexOf(',');
        String first = (comma >= 0 ? xfp.substring(0, comma) : xfp).trim();
        try {
            int port = Integer.parseInt(first);
            return (port > 0 && port <= 65535) ? port : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 从 RFC 7239 Forwarded 头中提取指定指令的值（如 {@code proto=https} 的 {@code https}）。 格式示例:
     * {@code Forwarded: for=192.0.2.60;proto=https;host=example.com}
     *
     * @param name
     *            指令名（{@code proto} / {@code host} 等），比较时忽略大小写
     */
    @Nullable
    private static String parseForwardedDirective(String forwarded, String name) {
        for (String segment : forwarded.split(";")) {
            segment = segment.trim();
            int eq = segment.indexOf('=');
            if (eq < 0) {
                continue;
            }
            if (!segment.substring(0, eq).trim().equalsIgnoreCase(name)) {
                continue;
            }
            // 值可能带引号: proto="https" 或 proto=https
            String value = segment.substring(eq + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            if (!value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /**
     * 是否信任转发头：读 {@code server.forward-headers-strategy} （NONE/FALSE 视为不信任；FRAMEWORK/NATIVE/其它非空视为信任）。
     */
    private boolean isForwardedHeadersEnabled() {
        String strategy = getWebContext().getProps().get(PropertiesConstant.FORWARD_HEADERS_STRATEGY, null);
        if (strategy == null || strategy.trim().isEmpty()) {
            return false;
        }
        String s = strategy.trim();
        return !("NONE".equalsIgnoreCase(s) || "FALSE".equalsIgnoreCase(s));
    }

    @Override
    public InputStream getBody() {
        getBodyBytes();
        if (largeBodyBuf != null) {
            // duplicate() 不递增 refCnt：读取期由 request 的 retain/acquire 保证 content 存活
            // （业务线程读 body 发生在 finally req.release() 之前），InputStream 仅请求处理期有效
            return new ByteBufInputStream(largeBodyBuf.duplicate(), false);
        }
        return new ByteArrayInputStream(body);
    }

    protected byte[] getBodyBytes() {
        if (body == null) {
            synchronized (this) {
                if (body == null) {
                    ByteBuf content = request.content();
                    int size = content.readableBytes();
                    if (size <= largeBodyLimit) {
                        body = ByteBufUtil.getBytes(content);
                    } else {
                        // duplicate() 创建共享视图但不递增 refCnt：largeBodyBuf 不持有独立引用，
                        // 存活由 request 引用链（retain/acquire/release）统一管理，无需单独配对
                        largeBodyBuf = content.duplicate();
                        body = EMPTY_BODY;
                    }
                }
            }
        }
        return body;
    }

    @Override
    public boolean hasBody() {
        return request.content().readableBytes() > 0;
    }

    // Netty 对未绑定/未连接（或已关闭）的通道返回 null：契约即 @Nullable。
    // 标注必须落在“分析器实际看到的声明”上——即此处覆写处，只标接口不生效。
    @Override
    @Nullable
    public InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) ctx.channel().localAddress();
    }

    @Override
    @Nullable
    public InetSocketAddress getRemoteAddress() {
        return (InetSocketAddress) ctx.channel().remoteAddress();
    }

    @Override
    public int getContentLength() {
        // 构造期缓存值：入站 buf 在同步收尾后可能已释放/被复用，
        // 现读 content() 会得到 0、陈旧值，或抛 IllegalReferenceCountException
        return contentLength;
    }

    @Override
    public void acquire() {
        ReferenceCountUtil.retain(request);
    }

    @Override
    public boolean release() {
        // largeBodyBuf 是 content 的共享视图（duplicate 不 +1），无独立引用需释放；
        // refCnt 递减本身原子，无需与 getBody() 互斥——读取期 content 存活由 acquire 保证。
        //
        // 级联释放响应未提交 buf 必须【只在最后一次 release（refCnt 归零）】执行：
        // 非最后一次的 release 包括 EventLoop 提交业务池后那次、以及 channelRead.finally 那次，
        // 它们发生时业务线程可能仍在写响应缓冲——若此时级联，会把正在被写的 buf 释放并置空
        // （旧实现无条件级联，属跨线程早释放竞态）。
        boolean last = ReferenceCountUtil.release(request);
        if (last && response != null) {
            // release() 幂等（置空 buf）：响应已提交时为空操作，未提交时兜底释放
            response.release();
        }
        return last;
    }

}
