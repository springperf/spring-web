package io.springperf.webtest.common;

import java.io.Serializable;
import java.util.Objects;

public class ApiResult<T> implements Serializable {
	private Integer code;
	private String msg;
	private T data;

	private ApiResult() {
	}

	private ApiResult(Integer code, String msg, T data) {
		this.code = code;
		this.msg = msg;
		this.data = data;
	}

	public static <T> ApiResult<T> ok(T data) {
		return ok(null, data);
	}

	public static <T> ApiResult<T> ok() {
		return ok(null);
	}

	public static <T> ApiResult<T> ok(String msg, T data) {
		ApiErrorCode resultCode = ApiErrorCode.SUCCESS;
		if (data instanceof Boolean && Boolean.FALSE.equals(data)) {
			resultCode = ApiErrorCode.FAILED;
		}

		return result(data, resultCode.getCode(), msg);
	}

	public static <T> ApiResult<T> state(boolean success) {
		return success ? ok() : failed();
	}

	public static <T> ApiResult<T> failed() {
		return result(null, ApiErrorCode.FAILED.getCode(), ApiErrorCode.FAILED.getMsg());
	}

	public static <T> ApiResult<T> failed(String msg) {
		return result(null, ApiErrorCode.FAILED.getCode(), msg);
	}

	public static <T> ApiResult<T> failed(ApiErrorCode errorCode) {
		return result(null, errorCode.getCode(), errorCode.getMsg());
	}

	public static <T> ApiResult<T> failed(ApiErrorCode errorCode, T data) {
		return result(data, errorCode.getCode(), errorCode.getMsg());
	}

	public static <T> ApiResult<T> failed(String msg, T data) {
		return result(data, ApiErrorCode.FAILED.getCode(), msg);
	}


	public static <T> ApiResult<T> failed(Integer code, String msg) {
		return result(null, code, msg);
	}

	public static <T> ApiResult<T> state(boolean success, String msg) {
		return success ? ok(msg, null) : failed(msg);
	}

	public static boolean check(ApiResult<?> r) {
		return Objects.isNull(r) ? false : r.checkSuccess();
	}

	private static <T> ApiResult<T> result(T data, Integer code, String msg) {
		return new ApiResult(code, msg, data);
	}

	public boolean checkSuccess() {
		return ApiErrorCode.SUCCESS.getCode().equals(this.code);
	}

	public Integer getCode() {
		return this.code;
	}

	public ApiResult<T> setCode(final Integer code) {
		this.code = code;
		return this;
	}

	public String getMsg() {
		return this.msg;
	}

	public ApiResult<T> setMsg(final String msg) {
		this.msg = msg;
		return this;
	}

	public T getData() {
		return this.data;
	}

	public ApiResult<T> setData(final T data) {
		this.data = data;
		return this;
	}
}
