package io.springperf.webtest.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static io.springperf.webtest.common.ApiErrorCode.*;


@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    protected static List<String> convertFiledErrors(List<FieldError> fieldErrors) {
        return Optional.ofNullable(fieldErrors).map(fieldErrorsInner -> fieldErrorsInner.stream()
                .map(fieldError -> fieldError.getDefaultMessage())
                .collect(Collectors.toList())).orElse(null);
    }

    /**
     * 业务异常
     *
     * @param e {@link BusinessException}
     */
    @ExceptionHandler({BusinessException.class})
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public ApiResult<?> handleError(BusinessException e) {
        log.error("业务异常：{}-{}", e.getCode(), e.getMessage(), e);
        String msg = Objects.equals(e.getCode(), e.getMessage()) ? null : e.getMessage();
        return ApiResult.failed(Integer.valueOf(e.getCode()), msg);
    }

     @ExceptionHandler(value = NotLoginException.class)
	@ResponseBody
	public ApiResult<?> handleError(NotLoginException e) {
		log.error("请求未授权：{}", e.getMessage(), e);
		return ApiResult.failed(UNAUTHORIZED);
	}

    /**
     * 验证异常处理 - 在 @RequestBody 上添加 @Validated 处触发
     *
     * @param e {@link MethodArgumentNotValidException}
     */
    @ExceptionHandler({MethodArgumentNotValidException.class})
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public ApiResult<?> handleError(MethodArgumentNotValidException e) {
        log.error("MethodArgumentNotValidException 参数校验失败:", e);
        ApiResult<?> apiResult = ApiResult.failed(PARAM_ERROR, this.convertFiledErrors(e.getBindingResult().getFieldErrors()));
        return apiResult;
    }

    /**
     * 验证异常处理 - form参数（对象参数，没有加 @RequestBody）触发
     *
     * @param e {@link BindException}
     */
    @ExceptionHandler({BindException.class})
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public ApiResult<?> handleError(BindException e) {
        log.error("BindException 参数校验失败：", e);
        ApiResult<?> apiResult = ApiResult.failed(PARAM_ERROR, this.convertFiledErrors(e.getBindingResult().getFieldErrors()));
        return apiResult;
    }

    /**
     * 非法参数异常处理
     *
     * @param e
     */
    @ExceptionHandler({IllegalArgumentException.class})
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public ApiResult<?> handleError(IllegalArgumentException e) {
        log.error("IllegalArgumentException 参数校验失败：{}", e.getMessage());
        return ApiResult.failed(PARAM_ERROR);
    }

    /**
     * 请求参数无法读取
     *
     * @param ex
     */
    @ExceptionHandler({HttpMessageNotReadableException.class})
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public ApiResult<?> handleError(HttpMessageNotReadableException ex) {
        log.error("消息不能读取：{}", ex.getMessage());
        return ApiResult.failed(PARAM_ERROR);
    }

    @ExceptionHandler({HttpRequestMethodNotSupportedException.class})
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public ApiResult<?> handleError(HttpRequestMethodNotSupportedException e) {
        log.error("不支持当前请求方法：{}", e.getMessage());
        return ApiResult.failed(METHOD_NOT_SUPPORTED);
    }

    @ExceptionHandler({HttpMediaTypeNotSupportedException.class})
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public ApiResult<?> handleError(HttpMediaTypeNotSupportedException e) {
        log.error("不支持当前媒体类型：{}", e.getMessage());
        return ApiResult.failed(MEDIA_TYPE_NOT_SUPPORTED);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public ApiResult<?> handleError(MethodArgumentTypeMismatchException ex) {
        log.error("服务器异常：{}", ex.getMessage());
        return ApiResult.failed(PARAM_ERROR);
    }

    /**
     * 自定义 REST 业务异常
     *
     * @param e 异常类型
     */
    @ExceptionHandler(value = Throwable.class)
    @ResponseBody
    public ApiResult<?> handleError(Throwable e) {
        // 系统内部异常，打印异常栈
        log.error("服务器异常：{}", e.getMessage(), e);
        return ApiResult.failed(INTERNAL_SERVER_ERROR);
    }

}
