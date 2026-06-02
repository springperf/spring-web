package io.springperf.webtest.common;

/**
 * API 错误枚举
 */
public enum ApiErrorCode {

	SUCCESS(                        0,  "操作成功"),
	FAILED(                         1,  "操作失败"),

    COMMON_SUCCESS(                 200, "请求成功"),

	UNAUTHORIZED(                   401, "请求未授权"),
	FORBIDDEN(                      403, "请求被拒绝"),
	NOT_FOUND(                      404, "未找到请求资源"),
	METHOD_NOT_SUPPORTED(           405, "不支持当前请求方法"),
	MEDIA_TYPE_NOT_SUPPORTED(       415, "不支持当前媒体类型"),
	PARAM_ERROR(                    400, "请求参数错误或缺失"),
	INTERNAL_SERVER_ERROR(          500, "服务器异常"),
	;

	private final Integer code;
	private final String msg;

	ApiErrorCode(Integer code, String msg) {
		this.code = code;
		this.msg = msg;
	}

    /**
     * 状态码
     */
    public Integer getCode() {
        return this.code;
    }
    /**
     * 消息
     */
    public String getMsg() {
        return this.msg;
    }
}
