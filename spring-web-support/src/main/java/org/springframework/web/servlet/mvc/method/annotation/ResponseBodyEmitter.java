/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.servlet.mvc.method.annotation;

import io.springperf.web.core.async.stream.StreamEmitter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.ObjectUtils;
import org.springframework.web.context.request.async.WebAsyncSupportUtils;

import java.io.IOException;

/**
 * A controller method return value type for asynchronous request processing
 * where one or more objects are written to the response.
 *
 * <p>While {@link org.springframework.web.context.request.async.DeferredResult}
 * is used to produce a single result, a {@code ResponseBodyEmitter} can be used
 * to send multiple objects where each object is written with a compatible
 * {@link org.springframework.http.converter.HttpMessageConverter}.
 *
 * <p>Supported as a return type on its own as well as within a
 * {@link org.springframework.http.ResponseEntity}.
 *
 * <pre>
 * &#064;RequestMapping(value="/stream", method=RequestMethod.GET)
 * public ResponseBodyEmitter handle() {
 * 	   ResponseBodyEmitter emitter = new ResponseBodyEmitter();
 * 	   // Pass the emitter to another component...
 * 	   return emitter;
 * }
 *
 * // in another thread
 * emitter.send(foo1);
 *
 * // and again
 * emitter.send(foo2);
 *
 * // and done
 * emitter.complete();
 * </pre>
 *
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 4.2
 */
public class ResponseBodyEmitter extends StreamEmitter {

    private EncodeToBytesFunction encodeToBytesFunction;

    /**
     * Create a new ResponseBodyEmitter instance.
     */
    public ResponseBodyEmitter() {
        super(false);
    }

    /**
     * Create a ResponseBodyEmitter with a custom timeout value.
     * <p>By default not set in which case the default configured in the MVC
     * Java Config or the MVC namespace is used, or if that's not set, then the
     * timeout depends on the default of the underlying server.
     *
     * @param timeout the timeout value in milliseconds
     */
    public ResponseBodyEmitter(Long timeout) {
        super(timeout, false);
    }


    /**
     * Return the configured timeout value, if any.
     */
    @Nullable
    public Long getTimeout() {
        return WebAsyncSupportUtils.getDeferredResultTimeout(getDeferredResult());
    }

    @Override
    protected void extendResponse(ServerHttpResponse response) {

    }

    @Override
    protected byte[] encodeToBytes(Object data) throws IOException {
        return encodeToBytesFunction.apply(data);
    }

    protected void setEncodeToBytesFunction(EncodeToBytesFunction encodeToBytesFunction) {
        this.encodeToBytesFunction = encodeToBytesFunction;
    }

    /**
     * Overloaded variant of {@link #send(Object)} that also accepts a MediaType
     * hint for how to serialize the given Object.
     *
     * @param object    the object to write
     * @param mediaType a MediaType hint for selecting an HttpMessageConverter
     * @throws IOException                     raised when an I/O error occurs
     * @throws java.lang.IllegalStateException wraps any other errors
     */
    public synchronized void send(Object object, @Nullable MediaType mediaType) throws IOException {
        if (mediaType == null) {
            send(object);
        } else {
            send(new DataWithMediaType(object, mediaType));
        }
    }


    @Override
    public String toString() {
        return "ResponseBodyEmitter@" + ObjectUtils.getIdentityHexString(this);
    }

    @FunctionalInterface
    public interface EncodeToBytesFunction {
        byte[] apply(Object data) throws IOException;
    }

    /**
     * A simple holder of data to be written along with a MediaType hint for
     * selecting a message converter to write with.
     */
    public static class DataWithMediaType {

        private final Object data;

        @Nullable
        private final MediaType mediaType;

        public DataWithMediaType(Object data, @Nullable MediaType mediaType) {
            this.data = data;
            this.mediaType = mediaType;
        }

        public Object getData() {
            return this.data;
        }

        @Nullable
        public MediaType getMediaType() {
            return this.mediaType;
        }
    }
}

