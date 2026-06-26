package io.springperf.web.core.resource;

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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
public class ResourceRequestHandler implements CustomInvoker {

    public static final Method HANDLE_METHOD = ReflectionUtils.findMethod(ResourceRequestHandler.class, "handleResourceRequest", WebServerHttpRequest.class, WebServerHttpResponse.class);

    private final ResourceHandlerRegistration registration;

    public ResourceHandlerRegistration getRegistration() {
        return registration;
    }

    private final ConcurrentMap<String, Resource> resourceCache = new ConcurrentHashMap<>();

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
        String resourcePath = reformatPath(path);

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

        if (resource == null || !resource.exists() || !resource.isReadable()) {
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

            // set Cache-Control
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

            // set Content-Encoding for gzip
            if (useGzip) {
                resp.getHeaders().add(HttpHeaders.CONTENT_ENCODING, "gzip");
            }

            // write resource
            resp.setStatusCode(HttpStatus.OK);
            resp.writeStream(resource.getInputStream());
        } catch (Exception e) {
            log.error("Failed to serve resource: {}", resourcePath, e);
            resp.sendError(HttpStatus.INTERNAL_SERVER_ERROR);
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
            Resource gzipResource = resolveResourceByUri(uri + ".gz");
            if (gzipResource != null && gzipResource.exists() && gzipResource.isReadable()) {
                return gzipResource;
            }
        } catch (Exception e) {
            log.debug("Failed to resolve gzip resource for: {}", resource.getDescription(), e);
        }
        return null;
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

            // check If-None-Match first (higher priority)
            List<String> ifNoneMatch = req.getHeaders().getIfNoneMatch();
            if (ifNoneMatch != null && !ifNoneMatch.isEmpty()) {
                for (String clientEtag : ifNoneMatch) {
                    if (clientEtag.equals(eTag) || clientEtag.equals("*")) {
                        resp.setStatusCode(HttpStatus.NOT_MODIFIED);
                        resp.getHeaders().setContentLength(-1);
                        return true;
                    }
                }
            }

            // check If-Modified-Since
            long ifModifiedSince = req.getHeaders().getIfModifiedSince();
            if (ifModifiedSince >= 0 && (lastModified / 1000 * 1000) <= ifModifiedSince) {
                resp.setStatusCode(HttpStatus.NOT_MODIFIED);
                resp.getHeaders().setContentLength(-1);
                return true;
            }
        } catch (Exception e) {
            log.warn("Failed to check not modified for resource", e);
        }
        return false;
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
        // check cache first
        Resource cached = resourceCache.get(path);
        if (cached != null && cached.exists()) {
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
     * Clear the internal resource cache.
     */
    public void clearCache() {
        resourceCache.clear();
    }

    public Method getHandleMethod() {
        return HANDLE_METHOD;
    }

    public List<Matcher> getMatchers() {
        HttpMethod[] httpMethods = new HttpMethod[]{HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST};
        Matcher matcher = new HttpMethodMatcher(httpMethods);
        return Arrays.asList(matcher);
    }

    @Override
    public String getType() {
        return "Resource";
    }
}
