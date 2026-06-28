package io.springperf.webtest.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话拦截器，Controller 方法调用前处理
 *
 * @author jy
 * @since 2023-12-04 14:54
 */
//@Configuration
public class SessionInterceptor implements HandlerInterceptor {

    /**
     * 用户会话缓存
     */
    private static Map<String, UserSession> userSessionMap = new ConcurrentHashMap<>();

    public static String getTokenValue(HttpServletRequest request) {
        String tokenName = "Authorization";
        String token = request.getHeader(tokenName);
        if (StringUtils.isEmpty(token)) {
            token = request.getParameter(tokenName);
        }
        if (!StringUtils.isEmpty(token)) {
            String tokenPrefix = "Bearer ";
            if (StringUtils.isEmpty(tokenPrefix)) {
                return token;
            }
            if (token.startsWith(tokenPrefix)) {
                token = token.substring(tokenPrefix.length());
            }
        }
        return token;
    }

    public static void login(UserSession userSession) {
        userSessionMap.put(userSession.getToken(), userSession);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (handler instanceof HandlerMethod) {
            HandlerMethod handlerMethod = (HandlerMethod) handler;
            Method method = handlerMethod.getMethod();
            //注解声明了无需认证
            LoginIgnore loginIgnore = method.getAnnotation(LoginIgnore.class);
            if (null != loginIgnore) {
                return true;
            }
            String tokenValue = getTokenValue(request);
            if (StringUtils.isEmpty(tokenValue)) {
                throw new NotLoginException();
            }
            return userSessionMap.containsKey(tokenValue);
        }
        // 通过拦截
        return true;
    }

    public static class UserSession {

        private final String userId;

        private final String userName;

        private final String token;

        public UserSession(String userId, String userName, String token) {
            this.userId = userId;
            this.userName = userName;
            this.token = token;
        }

        public String getUserId() {
            return userId;
        }

        public String getUserName() {
            return userName;
        }

        public String getToken() {
            return token;
        }
    }
}
