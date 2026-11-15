package com.coolpilot.insight.common.security;

import com.coolpilot.insight.common.security.RequireRole;
import com.coolpilot.insight.common.security.JwtUtil;
import com.coolpilot.insight.utils.TokenStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.lang.reflect.Method;

@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TokenStore tokenStore;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        HandlerMethod handlerMethod = (HandlerMethod) handler;
        Method method = handlerMethod.getMethod();

        if (!method.isAnnotationPresent(RequireRole.class)) {
            RequestContext.setUserId(null);
            RequestContext.setRole(null);
            return true;
        }

        String token = request.getHeader("Authorization");
        if (token == null || token.isEmpty()) {
            writeJson(response, 401, "未登录");
            return false;
        }

        try {
            if (!tokenStore.exists(token)) {
                writeJson(response, 401, "登录已过期");
                return false;
            }

            Long userId = jwtUtil.getUserId(token);
            Integer role = jwtUtil.getRole(token);

            RequireRole requireRole = method.getAnnotation(RequireRole.class);
            if (requireRole.role() > role) {
                writeJson(response, 403, "权限不足");
                return false;
            }

            RequestContext.setUserId(userId);
            RequestContext.setRole(role);
            return true;
        } catch (Exception e) {
            writeJson(response, 401, "Token无效");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        RequestContext.clear();
    }

    /**
     * 直接写 JSON 响应。
     *
     * <p>必须显式设置 charset：{@code response.getWriter()} 默认按 ISO-8859-1 编码，
     * 中文会变成 "????"。此处统一处理，避免每个分支重复。
     */
    private void writeJson(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
