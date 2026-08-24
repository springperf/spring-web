package io.springperf.web.support.servlet;

import javax.servlet.ServletException;

import java.security.Principal;

/**
 * 可插拔的认证器接口，用于 {@link PerfHttpServletRequest#login(String, String)} 的认证逻辑。
 * <p>用户可通过 Spring Bean 注入自定义实现，框架自动发现并调用。</p>
 */
@FunctionalInterface
public interface Authenticator {

    /**
     * 认证用户。
     *
     * @param username 用户名
     * @param password 密码
     * @return 认证成功时返回包含用户信息的 {@link Principal}，不可为 {@code null}
     * @throws ServletException 认证失败时抛出，描述失败原因
     */
    Principal authenticate(String username, String password) throws ServletException;
}