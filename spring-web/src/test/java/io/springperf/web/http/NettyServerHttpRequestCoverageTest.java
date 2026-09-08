package io.springperf.web.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import io.springperf.web.http.support.HttpInputMessagePart;
import io.springperf.web.http.support.NettyAttributeMessage;
import io.springperf.web.http.support.NettyMultipartWebRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NettyServerHttpRequestCoverageTest {

    @Mock
    WebContext webContext;
    @Mock
    ChannelHandlerContext ctx;
    @Mock
    ApplicationProperties props;

    @BeforeEach
    void setUp() {
        lenient().when(webContext.getProps()).thenReturn(props);
        lenient().when(props.getInt(anyString())).thenReturn(4096);
    }

    static class MultipartRequest extends NettyMultipartWebRequest {
        final MultiValueMap<String, NettyAttributeMessage> params = new LinkedMultiValueMap<>();
        final MultiValueMap<String, MultipartFile> files = new LinkedMultiValueMap<>();
        final MultiValueMap<String, HttpInputMessagePart> parts = new LinkedMultiValueMap<>();

        MultipartRequest(HttpPostRequestDecoder decoder, String uri) {
            super(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri, Unpooled.buffer(0)),
                    decoder, EmptyHttpHeaders.INSTANCE);
        }

        @Override
        public MultiValueMap<String, NettyAttributeMessage> getParameters() {
            return params;
        }

        @Override
        public MultiValueMap<String, MultipartFile> getFiles() {
            return files;
        }

        @Override
        public MultiValueMap<String, HttpInputMessagePart> getParts() {
            return parts;
        }
    }

    private static FullHttpRequest newRequest() {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test", Unpooled.buffer(0));
    }

    private static MultipartRequest newMultipartRequest(String uri) {
        HttpPostRequestDecoder decoder = mock(HttpPostRequestDecoder.class);
        lenient().when(decoder.getBodyHttpDatas()).thenReturn(Collections.emptyList());
        return new MultipartRequest(decoder, uri);
    }

    @Test
    void getNativeRequest_returnsUnderlyingRequest() {
        FullHttpRequest nativeRequest = newRequest();
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");
        assertSame(nativeRequest, req.getNativeRequest());
        nativeRequest.release();
    }

    @Test
    void getMethod_andGetMethodValue_reflectNativeRequest() {
        FullHttpRequest nativeRequest = newRequest();
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");
        assertEquals(org.springframework.http.HttpMethod.GET, req.getMethod());
        assertEquals("GET", req.getMethodValue());
        nativeRequest.release();
    }

    @Test
    void getRemoteAddress_delegatesToChannel() {
        Channel channel = mock(Channel.class);
        InetSocketAddress remote = new InetSocketAddress("127.0.0.1", 9999);
        lenient().when(ctx.channel()).thenReturn(channel);
        when(channel.remoteAddress()).thenReturn(remote);
        FullHttpRequest nativeRequest = newRequest();
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");
        assertEquals(remote, req.getRemoteAddress());
        nativeRequest.release();
    }

    @Test
    void parseParameters_multipartRequest_withAttributesAndErrors() throws Exception {
        MultipartRequest mp = newMultipartRequest("/test?q=1");
        NettyAttributeMessage good = mock(NettyAttributeMessage.class);
        when(good.getValue()).thenReturn("v1");
        NettyAttributeMessage bad = mock(NettyAttributeMessage.class);
        when(bad.getValue()).thenThrow(new IOException("boom"));
        mp.params.add("good", good);
        mp.params.add("bad", bad);
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, mp, "/test?q=1");
        MultiValueMap<String, String> params = req.getParameterMap();
        assertEquals("v1", params.getFirst("good"));
        assertNull(params.getFirst("bad"));
        assertEquals("1", params.getFirst("q"));
        req.release();
    }

    @Test
    void parseParameters_multipartRequest_emptyAttributes_fallsThroughToQuery() {
        MultipartRequest mp = newMultipartRequest("/test?q=hello");
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, mp, "/test?q=hello");
        MultiValueMap<String, String> params = req.getParameterMap();
        assertEquals("hello", params.getFirst("q"));
        req.release();
    }

    @Test
    void getMultiFileMap_andGetPartMap_nullForPlainRequest() {
        FullHttpRequest nativeRequest = newRequest();
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");
        assertNull(req.getMultiFileMap());
        assertNull(req.getPartMap());
        nativeRequest.release();
    }

    @Test
    void getMultiFileMap_andGetPartMap_returnMapsForMultipart() {
        MultipartRequest mp = newMultipartRequest("/test");
        MultipartFile file = mock(MultipartFile.class);
        HttpInputMessagePart part = mock(HttpInputMessagePart.class);
        mp.files.add("f1", file);
        mp.parts.add("p1", part);
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, mp, "/test");
        assertSame(mp.files, req.getMultiFileMap());
        assertSame(mp.parts, req.getPartMap());
        req.release();
    }
}