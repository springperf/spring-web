package io.springperf.web.support.servlet;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.CookieHeaderNames;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.context.PerfServletContext;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PerfHttpServletResponse extends AbstractFastFailHttpServletResponse {

    private WebServerHttpResponse response;
    private volatile String sameSite;
    private ServletAdapterContext adapterContext;
    private Locale locale;
    private volatile boolean calledOutputStream;
    private volatile boolean calledWriter;
    private PrintWriter cachedWriter;
    /** Writer 的字符编码器（缓冲在此）：提交前回调与 sendRedirect 都需先把缓冲刷入响应体。 */
    private OutputStreamWriter cachedEncoder;
    private ServletOutputStream cachedOutputStream;
    /** 业务是否已写入响应体（首次写入时把响应标记为 handled，确保请求收尾会提交）。 */
    private volatile boolean bodyWritten;

    private static final Pattern CHARSET_PATTERN =
            Pattern.compile(";\\s*charset\\s*=\\s*([^;\\s]+)", Pattern.CASE_INSENSITIVE);

    public PerfHttpServletResponse(WebServerHttpResponse response) {
        this.response = response;
        applyContainerResponseEncoding();
        registerBeforeCommit();
    }

    /**
     * 注册「提交前回调」：把 Writer 的编码缓冲刷入响应体。
     *
     * <p>Writer 采用 Tomcat 语义（{@code autoFlush=false}）：{@code write()} 不会立即落到响应体，
     * 若不在任何提交路径（一次性 flush / flushChunked / sendError）之前把编码缓冲刷出，
     * 未显式 flush 的写入会静默丢失。</p>
     */
    private void registerBeforeCommit() {
        response.setBeforeCommit(this::flushEncoderIntoBody);
    }

    /**
     * 业务首次写入响应体时标记「响应已由业务接管」。
     *
     * <p>Servlet 语义下容器在请求结束时会送出业务写入的内容；若不打这个标记，框架的收尾逻辑
     * （{@code flushResponse} 仅在 {@code isHandled()} 为真时提交）会认为无人写响应，
     * 导致「用 Writer/OutputStream 写了但没 flush」的处理器**响应永远不发出**（客户端挂到超时）。</p>
     */
    private void markBodyWritten() {
        if (!bodyWritten) {
            bodyWritten = true;
            response.setHandled();
        }
    }

    /** 把 Writer 的编码缓冲刷入响应体（不提交响应）。 */
    private void flushEncoderIntoBody() {
        if (cachedEncoder != null) {
            try {
                cachedEncoder.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * {@code server.servlet.encoding.charset}：容器级响应编码在适配器创建期写入
     * （早于业务代码，构成"默认值"；业务后续 setCharacterEncoding 仍可覆盖，
     * force-response=true 时该覆盖被忽略——见 {@link #setCharacterEncoding}）。
     * <p>rebind（Filter 包装）时对新 delegate 重新应用，避免容器编码在包装后丢失。</p>
     */
    private void applyContainerResponseEncoding() {
        try {
            io.springperf.web.support.servlet.context.PerfServletContext sc =
                    response.getWebContext().getWebComponent(
                            io.springperf.web.support.servlet.context.PerfServletContext.class);
            if (sc != null) {
                response.setCharacterEncoding(java.nio.charset.Charset.forName(sc.getResponseCharacterEncoding()));
            }
        } catch (Exception ignored) {
            // 无 WebContext/Servlet 组件（如单测桩环境）：保持委托默认 UTF-8
        }
    }

    public void setAdapterContext(ServletAdapterContext adapterContext) {
        this.adapterContext = adapterContext;
    }

    /**
     * 重新绑定底层的 {@link WebServerHttpResponse} 委托对象。
     * 当 WebFilter 包装了响应后调用。
     */
    public WebServerHttpResponse getResponse() {
        return response;
    }

    public void rebind(WebServerHttpResponse response) {
        if (this.response != response) {
            this.response = response;
            this.cachedWriter = null;
            this.cachedEncoder = null;
            // 新 delegate 是全新响应，业务写入标记随旧 delegate 一起作废
            this.bodyWritten = false;
            // 2-32：底层响应已替换，缓存的输出流失效，下次 getOutputStream 重新创建
            this.cachedOutputStream = null;
            applyContainerResponseEncoding();
            registerBeforeCommit();
        }
    }

    /**
     * 设置 SameSite 属性，作用于后续所有通过 {@link #addCookie(Cookie)} 添加的 Cookie。
     * 值为 {@code "Lax"}、{@code "Strict"} 或 {@code "None"}。
     */
    public void setSameSite(String sameSite) {
        this.sameSite = sameSite;
    }

    @Override public void setStatus(int sc) { response.setStatusCode(HttpStatusCode.valueOf(sc)); }
    @Override public int getStatus() { return response.getStatus().value(); }
    @Override public boolean isCommitted() { return response.isCommitted(); }
    @Override public boolean containsHeader(String name) { return response.getHeaders().containsKey(name); }

    @Override
    public void setDateHeader(String name, long date) {
        setHeader(name, formatDate(date));
    }

    @Override
    public void addDateHeader(String name, long date) {
        addHeader(name, formatDate(date));
    }

    @Override
    public void setIntHeader(String name, int value) {
        setHeader(name, Integer.toString(value));
    }

    @Override
    public void addIntHeader(String name, int value) {
        addHeader(name, Integer.toString(value));
    }

    @Override
    public void sendRedirect(String location) {
        if (location == null) {
            throw new IllegalArgumentException("Redirect location must not be null");
        }
        if (response.isCommitted()) {
            throw new IllegalStateException("Cannot send redirect: response already committed");
        }
        // 丢弃已缓冲的内容：重定向响应不得把先前写入的页面内容带给客户端
        // （对齐 Tomcat sendRedirect 的 clearBuffer=true 语义）。
        // 注意：此处只把 Writer 编码缓冲刷入响应体，【不】走 writer.flush()——后者按 Tomcat 语义会提交响应。
        flushEncoderIntoBody();
        response.resetBuffer();
        setStatus(HttpServletResponse.SC_FOUND);
        setHeader(HttpHeaders.Names.LOCATION, toAbsoluteLocation(location));
    }

    /**
     * 按 Servlet 规范把重定向 location 归一化为绝对 URL：
     * <ul>
     *   <li>外部绝对 URL（带 scheme，或以 {@code //} 开头的网络路径引用）原样返回；</li>
     *   <li>以 {@code /} 开头的站内路径：补 context-path 后拼上当前请求的 scheme://host[:port]；</li>
     *   <li>相对路径（如 {@code next}）：Servlet 规范要求容器转换为绝对 URL —— 先按当前请求 URI
     *       所在目录解析（含 {@code .}/{@code ..} 归一化，保留 query/fragment），再拼权威部分。</li>
     * </ul>
     */
    private String toAbsoluteLocation(String location) {
        if (hasScheme(location) || location.startsWith("//")) {
            return location;
        }
        String path = location.startsWith("/") ? withContextPath(location) : resolveRelative(location);
        HttpServletRequest req = adapterContext == null ? null : adapterContext.getRequest();
        if (req == null) {
            return path;
        }
        String scheme = req.getScheme();
        String serverName = req.getServerName();
        int port = req.getServerPort();
        StringBuilder sb = new StringBuilder(path.length() + 32);
        sb.append(scheme).append("://").append(serverName);
        if ((!"http".equals(scheme) || port != 80) && (!"https".equals(scheme) || port != 443)) {
            sb.append(':').append(port);
        }
        return sb.append(path).toString();
    }

    /** 站内绝对路径补 context-path（根路径或空配置时原样返回）。 */
    private String withContextPath(String path) {
        String ctxPath = response.getWebContext().getContextPath();
        if (ctxPath == null || ctxPath.isEmpty() || "/".equals(ctxPath)) {
            return path;
        }
        return ctxPath + path;
    }

    /**
     * 相对 location 解析为站内绝对路径：以当前请求 URI 的目录为基准，
     * {@code .} / {@code ..} 归一化，query 与 fragment 原样保留。
     */
    private String resolveRelative(String location) {
        int cut = indexOfAny(location, '?', '#');
        String relPath = cut < 0 ? location : location.substring(0, cut);
        String suffix = cut < 0 ? "" : location.substring(cut);
        HttpServletRequest req = adapterContext == null ? null : adapterContext.getRequest();
        String requestUri = req == null ? "/" : req.getRequestURI();
        int slash = requestUri == null ? -1 : requestUri.lastIndexOf('/');
        String dir = slash < 0 ? "/" : requestUri.substring(0, slash + 1);
        return normalizePath(dir + relPath) + suffix;
    }

    private static int indexOfAny(String s, char a, char b) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == a || c == b) {
                return i;
            }
        }
        return -1;
    }

    /** 路径段归一化：解析 {@code .} 与 {@code ..}（不越出根目录）。 */
    private static String normalizePath(String path) {
        if (path.indexOf('.') < 0) {
            return path;
        }
        java.util.Deque<String> segments = new java.util.ArrayDeque<>();
        int start = 0;
        int len = path.length();
        boolean trailingSlash = len > 0 && path.charAt(len - 1) == '/';
        while (start <= len) {
            int end = path.indexOf('/', start);
            String seg = end < 0 ? path.substring(start) : path.substring(start, end);
            if (".".equals(seg)) {
                // 忽略
            } else if ("..".equals(seg)) {
                if (!segments.isEmpty()) {
                    segments.removeLast();
                }
            } else if (!seg.isEmpty()) {
                segments.addLast(seg);
            }
            if (end < 0) {
                break;
            }
            start = end + 1;
        }
        StringBuilder sb = new StringBuilder(path.length());
        for (String seg : segments) {
            sb.append('/').append(seg);
        }
        if (sb.length() == 0) {
            sb.append('/');
        } else if (trailingSlash) {
            sb.append('/');
        }
        return sb.toString();
    }

    /** 是否带 URI scheme（形如 {@code http:} / {@code mailto:}），需在 query 之前的冒号判定。 */
    private static boolean hasScheme(String location) {
        for (int i = 0; i < location.length(); i++) {
            char c = location.charAt(i);
            if (c == ':') {
                return i > 0;
            }
            if (c == '/' || c == '?' || c == '#') {
                return false;
            }
        }
        return false;
    }

    @Override
    public String encodeURL(String url) {
        return encodeSessionUrl(url);
    }

    @Override
    public String encodeRedirectURL(String url) {
        return encodeSessionUrl(url);
    }

    private String encodeSessionUrl(String url) {
        if (url == null || adapterContext == null) {
            return url;
        }
        HttpServletRequest request = adapterContext.getRequest();
        if (request == null) {
            return url;
        }
        // 仅在 tracking-modes 含 URL 时重写（默认仅 COOKIE，不重写，对齐 Servlet 规范）
        ServletContext servletContext = request.getServletContext();
        if (servletContext == null
                || !servletContext.getEffectiveSessionTrackingModes().contains(SessionTrackingMode.URL)) {
            return url;
        }
        if (request.isRequestedSessionIdFromCookie()) {
            return url;
        }
        String sessionId = request.getRequestedSessionId();
        if (sessionId == null) {
            // URL 跟踪模式下（通常无 Cookie），客户端会话 id 为空——回退用当前会话 id
            // （Servlet 规范：encodeURL 需携带会话标识，否则纯 URL 模式跨请求丢会话）
            HttpSession session = request.getSession(false);
            if (session == null) {
                return url;
            }
            sessionId = session.getId();
        }
        if (url.contains(";jsessionid=")) {
            return url;
        }
        int fragmentIdx = url.indexOf('#');
        String beforeFragment = fragmentIdx >= 0 ? url.substring(0, fragmentIdx) : url;
        String afterFragment = fragmentIdx >= 0 ? url.substring(fragmentIdx) : "";
        int queryIdx = beforeFragment.indexOf('?');
        String path = queryIdx >= 0 ? beforeFragment.substring(0, queryIdx) : beforeFragment;
        String query = queryIdx >= 0 ? beforeFragment.substring(queryIdx) : "";
        return path + ";jsessionid=" + sessionId + query + afterFragment;
    }

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US).withZone(ZoneId.of("GMT"));

    private static String formatDate(long date) {
        return DATE_FORMAT.format(Instant.ofEpochMilli(date));
    }

    @Override
    public void addCookie(Cookie cookie) {
        DefaultCookie nettyCookie = new DefaultCookie(cookie.getName(), cookie.getValue() != null ? cookie.getValue() : "");
        if (cookie.getDomain() != null) {
            nettyCookie.setDomain(cookie.getDomain());
        }
        if (cookie.getPath() != null) {
            nettyCookie.setPath(cookie.getPath());
        }
        nettyCookie.setMaxAge(cookie.getMaxAge());
        nettyCookie.setSecure(cookie.getSecure());
        nettyCookie.setHttpOnly(cookie.isHttpOnly());
        if (sameSite != null) {
            nettyCookie.setSameSite(CookieHeaderNames.SameSite.valueOf(sameSite));
        }
        response.getHeaders().add(HttpHeaders.Names.SET_COOKIE, ServerCookieEncoder.STRICT.encode(nettyCookie));
    }
    @Override public void setHeader(String name, String value) { response.getHeaders().set(name, value); }
    @Override public void addHeader(String name, String value) { response.getHeaders().add(name, value); }
    @Override public String getHeader(String name) { return response.getHeaders().getFirst(name); }
    @Override public Collection<String> getHeaders(String name) { return response.getHeaders().get(name); }
    @Override public Collection<String> getHeaderNames() { return response.getHeaders().keySet(); }
    @Override
    public void setContentType(String type) {
        setHeader(HttpHeaders.Names.CONTENT_TYPE, type);
        if (type != null) {
            Matcher m = CHARSET_PATTERN.matcher(type);
            if (m.find()) {
                String charset = m.group(1);
                try {
                    setCharacterEncoding(charset);
                } catch (Exception ignored) {
                }
            }
        }
    }
    @Override public String getContentType() { return getHeader(HttpHeaders.Names.CONTENT_TYPE); }
    @Override public String getCharacterEncoding() { return response.getCharacterEncoding() == null ? null : response.getCharacterEncoding().name(); }
    @Override public void setCharacterEncoding(String charset) {
        // server.servlet.encoding.force-response=true：忽略业务显式设置（保持 ServletContext 配置 charset）
        PerfServletContext ctx = resolvePerfServletContext();
        if (ctx != null && ctx.isForceResponseEncoding()) {
            return;
        }
        response.setCharacterEncoding(Charset.forName(charset));
    }

    /** 解析当前请求关联的 {@link PerfServletContext}（用于 force-response 判定）；不可用时返回 null。 */
    private PerfServletContext resolvePerfServletContext() {
        if (adapterContext != null && adapterContext.getPerfRequest() != null) {
            ServletContext sc = adapterContext.getPerfRequest().getServletContext();
            if (sc instanceof PerfServletContext) {
                return (PerfServletContext) sc;
            }
        }
        return null;
    }

    @Override
    public void setContentLength(int len) {
        if (len >= 0) {
            setHeader(HttpHeaders.Names.CONTENT_LENGTH, Integer.toString(len));
        }
    }

    @Override
    public void setContentLengthLong(long len) {
        if (len >= 0) {
            setHeader(HttpHeaders.Names.CONTENT_LENGTH, Long.toString(len));
        }
    }

    @Override
    public void setLocale(Locale loc) {
        this.locale = loc;
        if (loc != null) {
            response.getHeaders().set(HttpHeaders.Names.CONTENT_LANGUAGE, loc.toLanguageTag());
        }
    }

    @Override
    public Locale getLocale() {
        return locale != null ? locale : Locale.getDefault();
    }

    @Override
    public ServletOutputStream getOutputStream() {
        if (calledWriter) {
            throw new IllegalStateException("getWriter() has already been called");
        }
        calledOutputStream = true;
        // 2-32：规范同一 ServletResponse 的多次 getOutputStream() 必须返回同一实例
        if (cachedOutputStream == null) {
            cachedOutputStream = new ServletOutputStream() {
                @Override public boolean isReady() { return true; }
                @Override public void setWriteListener(WriteListener writeListener) { throw new UnsupportedOperationException("Non-blocking IO is not supported"); }
                @Override public void write(int b) throws IOException { markBodyWritten(); response.getBody().write(b); }
                @Override public void print(String s) throws IOException { markBodyWritten(); response.getBody().write(s.getBytes(getCharacterEncoding())); }
                @Override public void write(byte[] b) throws IOException { markBodyWritten(); response.getBody().write(b); }
                @Override public void write(byte[] b, int off, int len) throws IOException { markBodyWritten(); response.getBody().write(b, off, len); }
                @Override public void flush() throws IOException { commitChunked(); }
            };
        }
        return cachedOutputStream;
    }

    @Override public PrintWriter getWriter() throws IOException {
        if (calledOutputStream) {
            throw new IllegalStateException("getOutputStream() has already been called");
        }
        calledWriter = true;
        if (cachedWriter == null) {
            // 必须每次写入都解析「当前」响应体：
            // 每次提交（flushChunked/一次性 flush）后底层 ByteBuf 会被转移给 Netty 并置空换新，
            // 若在 getWriter() 时捕获一次 getBody()，后续写入会落进已转移的旧缓冲 → 静默丢失
            // （渐进式输出的第二次 flush 就丢内容）。
            OutputStream dynamicBody = new OutputStream() {
                @Override public void write(int b) throws IOException { markBodyWritten(); response.getBody().write(b); }
                @Override public void write(byte[] b) throws IOException { markBodyWritten(); response.getBody().write(b); }
                @Override public void write(byte[] b, int off, int len) throws IOException { markBodyWritten(); response.getBody().write(b, off, len); }
                @Override public void flush() throws IOException { response.getBody().flush(); }
            };
            cachedEncoder = new OutputStreamWriter(dynamicBody, getCharacterEncoding());
            // 对齐 Tomcat：autoFlush=false（println 不提交，仅显式 flush()/flushBuffer() 提交），
            // flush() 覆写为「编码缓冲落响应体 + 提交并写出」（见 CommitOnFlushPrintWriter）。
            cachedWriter = new CommitOnFlushPrintWriter(cachedEncoder, this);
        }
        return cachedWriter;
    }
    @Override public void flushBuffer() {
        // Tomcat 语义：flushBuffer() 提交响应并以 chunked 帧写出，之后仍可继续写入（渐进式输出）
        flushEncoderIntoBody();
        commitChunked();
    }

    @Override public int getBufferSize() { return response.getBufferSize(); }

    @Override
    public void reset() {
        // Servlet 规范 §5.6：响应已提交后 reset 必须抛 IllegalStateException
        if (response.isCommitted()) {
            throw new IllegalStateException("Cannot reset: response already committed");
        }
        response.getHeaders().clear();
        response.setStatusCode(HttpStatus.OK);
        resetBuffer();
    }

    @Override
    public void resetBuffer() {
        // Servlet 规范 §5.6：响应已提交后 resetBuffer 必须抛 IllegalStateException
        // （已发到线上的内容无法收回）。forward/sendRedirect 均先检查 isCommitted，不受影响。
        if (response.isCommitted()) {
            throw new IllegalStateException("Cannot reset buffer: response already committed");
        }
        // 先把 Writer 编码缓冲刷入响应体再清空，否则「写入后未 flush 就 resetBuffer」的
        // 内容仍滞留在编码缓冲里，无法被真正丢弃（提交前回调会把它们刷出来）
        flushEncoderIntoBody();
        response.resetBuffer();
    }

    @Override
    public void sendError(int sc) {
        if (response.isCommitted()) {
            throw new IllegalStateException("Cannot send error: response already committed");
        }
        HttpStatus status = HttpStatus.resolve(sc);
        if (status != null) {
            response.sendError(status, null, null, traceParam(), messageParam(), errorsParam());
        } else {
            // 非标准状态码（如 499/599/507）：HttpStatus 无法表示，按原始码值写入
            response.sendError(HttpStatusCode.valueOf(sc), null, null, traceParam(), messageParam(), errorsParam());
        }
    }
    @Override
    public void sendError(int sc, String msg) {
        if (response.isCommitted()) {
            throw new IllegalStateException("Cannot send error: response already committed");
        }
        HttpStatus status = HttpStatus.resolve(sc);
        if (status != null) {
            response.sendError(status, msg, null, traceParam(), messageParam(), errorsParam());
        } else {
            response.sendError(HttpStatusCode.valueOf(sc), msg, null, traceParam(), messageParam(), errorsParam());
        }
    }

    /*
     * server.error.include-message / include-stacktrace / include-binding-errors 的 on-param 模式依赖
     * 请求参数（message/trace/errors）是否命中；servlet 的 sendError 由本类发起，只有这里有请求上下文，
     * 因此在此解析并透传——否则 servlet 路径的 on-param 永远不生效（参数被硬编码为未命中）。
     */
    private boolean messageParam() {
        return paramPresent("message");
    }

    private boolean traceParam() {
        return paramPresent("trace");
    }

    private boolean errorsParam() {
        return paramPresent("errors");
    }

    private boolean paramPresent(String name) {
        if (adapterContext == null) {
            return false;
        }
        HttpServletRequest request = adapterContext.getRequest();
        if (request == null) {
            return false;
        }
        String value = request.getParameter(name);
        // 对齐 Boot：?x=false 视为未命中（显式关闭），其余（含无值）视为命中
        return value != null && !"false".equals(value);
    }

    /**
     * 提交响应头并以 chunked 帧写出已缓冲内容（Tomcat：{@code flushBuffer()} 与 {@code flush()}
     * 都会提交响应且之后仍可继续写入）。首次调用提交、后续调用续帧，具体由底层响应处理。
     */
    private void commitChunked() {
        try {
            response.flushChunked();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Tomcat 对齐的 {@link PrintWriter}：{@code autoFlush=false}（{@code println} 不提交响应），
     * 显式 {@link #flush()} 则「编码缓冲落响应体 + 提交并写出」，即 Servlet 规范中
     * {@code ServletResponse#getWriter().flush()} 的「提交响应」语义。
     */
    static final class CommitOnFlushPrintWriter extends PrintWriter {

        private final PerfHttpServletResponse owner;

        CommitOnFlushPrintWriter(OutputStreamWriter encoder, PerfHttpServletResponse owner) {
            super(encoder, false);
            this.owner = owner;
        }

        // 所有写入入口都标记「业务已写响应体」：PrintWriter 的 write/print/println/format 最终
        // 都会走到这几个方法，据此判定是否需要框架在请求收尾提交响应（不覆盖 getWriter() 本身，
        // 避免「只取 Writer 未写、却由返回值写出」的场景被误判为已接管）。

        @Override
        public void write(int c) {
            owner.markBodyWritten();
            super.write(c);
        }

        @Override
        public void write(char[] buf, int off, int len) {
            owner.markBodyWritten();
            super.write(buf, off, len);
        }

        @Override
        public void write(String s, int off, int len) {
            owner.markBodyWritten();
            super.write(s, off, len);
        }

        @Override
        public void write(char[] buf) {
            owner.markBodyWritten();
            super.write(buf);
        }

        @Override
        public void write(String s) {
            owner.markBodyWritten();
            super.write(s);
        }

        @Override
        public void flush() {
            // 先让编码缓冲落入响应体，再提交（否则本次 flush 写入的字符会被漏掉）
            super.flush();
            if (owner.bodyWritten) {
                owner.commitChunked();
            }
        }
    }
}