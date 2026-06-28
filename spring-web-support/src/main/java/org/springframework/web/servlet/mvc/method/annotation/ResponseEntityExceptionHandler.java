/*
 * Copyright 2002-2019 the original author or authors.
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

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Convenient base class for {@link ControllerAdvice @ControllerAdvice} classes
 * that wish to provide centralized exception handling across all
 * {@code @RequestMapping} methods through {@code @ExceptionHandler} methods.
 *
 * <p>This shim replicates the API of Spring MVC's
 * {@code ResponseEntityExceptionHandler} without requiring the full
 * {@code spring-webmvc} module. All protected methods return
 * {@link ResponseEntity} and can be overridden in subclasses.
 *
 * <p>Usage:
 * <pre class="code">
 * &#064;ControllerAdvice
 * public class MyExceptionHandler extends ResponseEntityExceptionHandler {
 *
 *     &#064;Override
 *     protected ResponseEntity&lt;Object&gt; handleHttpRequestMethodNotSupported(
 *             HttpRequestMethodNotSupportedException ex,
 *             HttpHeaders headers, HttpStatus status, WebRequest request) {
 *         return handleExceptionInternal(ex, "custom body", headers, status, request);
 *     }
 * }
 * </pre>
 *
 * @author Rossen Stoyanchev
 * @since 3.2
 */
@ControllerAdvice
public class ResponseEntityExceptionHandler {

    /**
     * Provides handling for a standard set of exceptions, delegating to
     * individual handler methods.
     */
    @ExceptionHandler({
            org.springframework.web.HttpRequestMethodNotSupportedException.class,
            org.springframework.web.HttpMediaTypeNotSupportedException.class,
            org.springframework.web.HttpMediaTypeNotAcceptableException.class,
            MissingPathVariableException.class,
            MissingServletRequestParameterException.class,
            ServletRequestBindingException.class,
            org.springframework.beans.ConversionNotSupportedException.class,
            org.springframework.beans.TypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.http.converter.HttpMessageNotWritableException.class,
            org.springframework.web.bind.MethodArgumentNotValidException.class,
            MissingServletRequestPartException.class,
            org.springframework.validation.BindException.class,
            NoHandlerFoundException.class,
            AsyncRequestTimeoutException.class
    })
    @Nullable
    public ResponseEntity<Object> handleException(Exception ex, WebRequest request) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        HttpStatus status;

        if (ex instanceof org.springframework.web.HttpRequestMethodNotSupportedException) {
            return handleHttpRequestMethodNotSupported(
                    (org.springframework.web.HttpRequestMethodNotSupportedException) ex, headers, HttpStatus.METHOD_NOT_ALLOWED, request);
        } else if (ex instanceof org.springframework.web.HttpMediaTypeNotSupportedException) {
            return handleHttpMediaTypeNotSupported(
                    (org.springframework.web.HttpMediaTypeNotSupportedException) ex, headers, HttpStatus.UNSUPPORTED_MEDIA_TYPE, request);
        } else if (ex instanceof org.springframework.web.HttpMediaTypeNotAcceptableException) {
            return handleHttpMediaTypeNotAcceptable(
                    (org.springframework.web.HttpMediaTypeNotAcceptableException) ex, headers, HttpStatus.NOT_ACCEPTABLE, request);
        } else if (ex instanceof MissingPathVariableException) {
            return handleMissingPathVariable(
                    (MissingPathVariableException) ex, headers, HttpStatus.INTERNAL_SERVER_ERROR, request);
        } else if (ex instanceof MissingServletRequestParameterException) {
            return handleMissingServletRequestParameter(
                    (MissingServletRequestParameterException) ex, headers, HttpStatus.BAD_REQUEST, request);
        } else if (ex instanceof ServletRequestBindingException) {
            return handleServletRequestBindingException(
                    (ServletRequestBindingException) ex, headers, HttpStatus.BAD_REQUEST, request);
        } else if (ex instanceof org.springframework.beans.ConversionNotSupportedException) {
            return handleConversionNotSupported(
                    (org.springframework.beans.ConversionNotSupportedException) ex, headers, HttpStatus.INTERNAL_SERVER_ERROR, request);
        } else if (ex instanceof org.springframework.beans.TypeMismatchException) {
            return handleTypeMismatch(
                    (org.springframework.beans.TypeMismatchException) ex, headers, HttpStatus.BAD_REQUEST, request);
        } else if (ex instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
            return handleHttpMessageNotReadable(
                    (org.springframework.http.converter.HttpMessageNotReadableException) ex, headers, HttpStatus.BAD_REQUEST, request);
        } else if (ex instanceof org.springframework.http.converter.HttpMessageNotWritableException) {
            return handleHttpMessageNotWritable(
                    (org.springframework.http.converter.HttpMessageNotWritableException) ex, headers, HttpStatus.INTERNAL_SERVER_ERROR, request);
        } else if (ex instanceof org.springframework.web.bind.MethodArgumentNotValidException) {
            return handleMethodArgumentNotValid(
                    (org.springframework.web.bind.MethodArgumentNotValidException) ex, headers, HttpStatus.BAD_REQUEST, request);
        } else if (ex instanceof MissingServletRequestPartException) {
            return handleMissingServletRequestPart(
                    (MissingServletRequestPartException) ex, headers, HttpStatus.BAD_REQUEST, request);
        } else if (ex instanceof org.springframework.validation.BindException) {
            return handleBindException(
                    (org.springframework.validation.BindException) ex, headers, HttpStatus.BAD_REQUEST, request);
        } else if (ex instanceof NoHandlerFoundException) {
            return handleNoHandlerFoundException(
                    (NoHandlerFoundException) ex, headers, HttpStatus.NOT_FOUND, request);
        } else if (ex instanceof AsyncRequestTimeoutException) {
            return handleAsyncRequestTimeoutException(
                    (AsyncRequestTimeoutException) ex, headers, HttpStatus.SERVICE_UNAVAILABLE, request);
        } else {
            return handleExceptionInternal(ex, null, headers, HttpStatus.INTERNAL_SERVER_ERROR, request);
        }
    }

    // ---- Individual handler methods (all overridable) ----

    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException ex,
            HttpHeaders headers, HttpStatus status, WebRequest request) {
        return handleExceptionInternal(ex, null, headers, status, request);
    }

    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            org.springframework.web.HttpMediaTypeNotSupportedException ex,
            HttpHeaders headers, HttpStatus status, WebRequest request) {
        return handleExceptionInternal(ex, null, headers, status, request);
    }

    protected ResponseEntity<Object> handleHttpMediaTypeNotAcceptable(
            org.springframework.web.HttpMediaTypeNotAcceptableException ex,
            HttpHeaders headers, HttpStatus status, WebRequest request) {
        return handleExceptionInternal(ex, null, headers, status, request);
    }

    protected ResponseEntity<Object> handleMissingPathVariable(
            MissingPathVariableException ex,
            HttpHeaders headers, HttpStatus status, WebRequest request) {
        return handleExceptionInternal(ex, null, headers, status, request);
    }

    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex,
            HttpHeaders headers, HttpStatus status, WebRequest request) {
        return handleExceptionInternal(ex, null, headers, status, request);
    }

    protected ResponseEntity<Object> handleServletRequestBindingException(
            ServletRequestBindingException ex,
            HttpHeaders headers, HttpStatus status, WebRequest request) {
        return handleExceptionInternal(ex, null, headers, status, request);
    }

    protected ResponseEntity<Object> handleConversionNotSupported(
            org.springframework.beans.ConversionNotSupportedException ex,
            HttpHeaders headers, HttpStatus status, WebRequest request) {
        return handleExceptionInternal(ex, null, headers, status, request);
    }

    protected ResponseEntity<Object> handleTypeMismatch(
            org.springframework.beans.TypeMismatchException ex,
            HttpHeaders headers, HttpStatus status, WebRequest request) {
        return handleExceptionInternal(ex, null, headers, status, request);
    }

    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            org.springframework.http.converter.HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatus status, WebRequest request) {
        return handleExceptionInternal(ex, null, headers, status, request);
    }

    protected ResponseEntity<Object> handleHttpMessageNotWritable(
            org.springframework.http.converter.HttpMessageNotWritableException ex,
            HttpHeaders headers, HttpStatus status, WebRequest request) {
        return handleExceptionInternal(ex, null, headers, status, request);
    }

    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            org.springframework.web.bind.MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatus status, WebRequest request) {
        return handleExceptionInternal(ex, null, headers, status, request);
    }

    protected ResponseEntity<Object> handleMissingServletRequestPart(
            MissingServletRequestPartException ex,
            HttpHeaders headers, HttpStatus status, WebRequest request) {
        return handleExceptionInternal(ex, null, headers, status, request);
    }

    protected ResponseEntity<Object> handleBindException(
            org.springframework.validation.BindException ex,
            HttpHeaders headers, HttpStatus status, WebRequest request) {
        return handleExceptionInternal(ex, null, headers, status, request);
    }

    protected ResponseEntity<Object> handleNoHandlerFoundException(
            NoHandlerFoundException ex,
            HttpHeaders headers, HttpStatus status, WebRequest request) {
        return handleExceptionInternal(ex, null, headers, status, request);
    }

    protected ResponseEntity<Object> handleAsyncRequestTimeoutException(
            AsyncRequestTimeoutException ex,
            HttpHeaders headers, HttpStatus status, WebRequest request) {
        return handleExceptionInternal(ex, null, headers, status, request);
    }

    /**
     * Centralized hook for creating the response entity. Subclasses can
     * override this to add common headers, modify the body, etc.
     */
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body,
            HttpHeaders headers, HttpStatus status, WebRequest request) {

        if (HttpStatus.INTERNAL_SERVER_ERROR.equals(status)) {
            request.setAttribute("jakarta.servlet.error.exception", ex, 0 /* SCOPE_REQUEST */);
        }
        return new ResponseEntity<>(body, headers, status);
    }
}
