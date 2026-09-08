package io.springperf.web.websocket.jsr;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class JsrCodecRegistryTest {

    @ServerEndpoint(value = "/ws", decoders = {MyTextDecoder.class, MyBinaryDecoder.class},
            encoders = {MyTextEncoder.class, MyBinaryEncoder.class})
    static class Endpoint {}

    public static class MyTextDecoder implements Decoder.Text<MyPojo> {
        @Override public MyPojo decode(String s) { return new MyPojo(s.toUpperCase()); }
        @Override public boolean willDecode(String s) { return true; }
        @Override public void init(EndpointConfig config) {}
        @Override public void destroy() {}
    }

    public static class MyBinaryDecoder implements Decoder.Binary<MyPojo> {
        @Override public MyPojo decode(ByteBuffer b) {
            byte[] arr = new byte[b.remaining()];
            b.get(arr);
            return new MyPojo(new String(arr, StandardCharsets.UTF_8));
        }
        @Override public boolean willDecode(ByteBuffer b) { return true; }
        @Override public void init(EndpointConfig config) {}
        @Override public void destroy() {}
    }

    public static class MyTextEncoder implements Encoder.Text<MyPojo> {
        @Override public String encode(MyPojo o) { return o.getValue(); }
        @Override public void init(EndpointConfig config) {}
        @Override public void destroy() {}
    }

    public static class MyBinaryEncoder implements Encoder.Binary<MyPojo> {
        @Override public ByteBuffer encode(MyPojo o) {
            return ByteBuffer.wrap(o.getValue().getBytes(StandardCharsets.UTF_8));
        }
        @Override public void init(EndpointConfig config) {}
        @Override public void destroy() {}
    }

    public static class MyPojo {
        private final String value;
        public MyPojo(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    private JsrEndpointConfigAdapter config(Class<?> endpointClass) {
        return new JsrEndpointConfigAdapter(new JsrEndpointMetadata(endpointClass));
    }

    private JsrCodecRegistry registry(Class<?> endpointClass) {
        return new JsrCodecRegistry(config(endpointClass));
    }

    @Test
    void decodeText_stringType_returnsAsIs() throws DecodeException {
        JsrCodecRegistry registry = registry(Endpoint.class);
        assertSame("hello", registry.decodeText("hello", String.class));
    }

    @Test
    void decodeText_pojoType_usesTextDecoder() throws DecodeException {
        JsrCodecRegistry registry = registry(Endpoint.class);
        Object result = registry.decodeText("hello", MyPojo.class);
        assertTrue(result instanceof MyPojo);
        assertEquals("HELLO", ((MyPojo) result).getValue());
    }

    @Test
    void decodeText_noDecoder_throws() {
        JsrCodecRegistry registry = registry(Endpoint.class);
        assertThrows(DecodeException.class, () -> registry.decodeText("x", StringBuilder.class));
    }

    @Test
    void decodeBinary_byteArrayType() throws DecodeException {
        JsrCodecRegistry registry = registry(Endpoint.class);
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        Object result = registry.decodeBinary(ByteBuffer.wrap(data), byte[].class);
        assertArrayEquals(data, (byte[]) result);
    }

    @Test
    void decodeBinary_byteBufferType_returnsSameBuffer() throws DecodeException {
        JsrCodecRegistry registry = registry(Endpoint.class);
        ByteBuffer buf = ByteBuffer.wrap("abc".getBytes(StandardCharsets.UTF_8));
        assertSame(buf, registry.decodeBinary(buf, ByteBuffer.class));
    }

    @Test
    void decodeBinary_pojoType_usesBinaryDecoder() throws DecodeException {
        JsrCodecRegistry registry = registry(Endpoint.class);
        Object result = registry.decodeBinary(
                ByteBuffer.wrap("data".getBytes(StandardCharsets.UTF_8)), MyPojo.class);
        assertTrue(result instanceof MyPojo);
        assertEquals("data", ((MyPojo) result).getValue());
    }

    @Test
    void decodeBinary_noDecoder_throws() {
        JsrCodecRegistry registry = registry(Endpoint.class);
        assertThrows(DecodeException.class,
                () -> registry.decodeBinary(ByteBuffer.wrap(new byte[0]), StringBuilder.class));
    }

    @Test
    void encode_preferText_usesTextEncoder() throws EncodeException {
        JsrCodecRegistry registry = registry(Endpoint.class);
        JsrCodecRegistry.EncodedPayload payload = registry.encode(new MyPojo("x"), true);
        assertTrue(payload.isText());
        assertEquals("x", payload.getText());
        assertNull(payload.getBinary());
    }

    @Test
    void encode_noTextEncoder_fallsBackToBinary() throws EncodeException {
        @ServerEndpoint(value = "/b", encoders = {MyBinaryEncoder.class})
        class BinOnly {}
        JsrCodecRegistry registry = registry(BinOnly.class);
        JsrCodecRegistry.EncodedPayload payload = registry.encode(new MyPojo("x"), true);
        assertFalse(payload.isText());
        assertNotNull(payload.getBinary());
    }

    @Test
    void encode_preferBinary_usesBinaryEncoder() throws EncodeException {
        JsrCodecRegistry registry = registry(Endpoint.class);
        JsrCodecRegistry.EncodedPayload payload = registry.encode(new MyPojo("x"), false);
        assertFalse(payload.isText());
        assertNotNull(payload.getBinary());
    }

    @Test
    void encode_noEncoder_throws() {
        @ServerEndpoint(value = "/n")
        class NoEncoder {}
        JsrCodecRegistry registry = registry(NoEncoder.class);
        assertThrows(EncodeException.class, () -> registry.encode(new MyPojo("x"), true));
    }

    @Test
    void destroy_invokesDestroyOnInstances() {
        JsrCodecRegistry registry = registry(Endpoint.class);
        assertDoesNotThrow(registry::destroy);
    }

    @Test
    void instantiationFailure_throwsIllegalState() {
        @ServerEndpoint(value = "/bad", decoders = {NoNoArgDecoder.class})
        class BadEndpoint {}
        assertThrows(IllegalStateException.class, () -> registry(BadEndpoint.class));
    }

    public static class NoNoArgDecoder implements Decoder.Text<MyPojo> {
        public NoNoArgDecoder(String arg) {}
        @Override public MyPojo decode(String s) { return null; }
        @Override public boolean willDecode(String s) { return true; }
        @Override public void init(EndpointConfig config) {}
        @Override public void destroy() {}
    }
}
