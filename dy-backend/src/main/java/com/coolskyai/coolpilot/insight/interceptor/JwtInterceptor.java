package com.coolskyai.coolpilot.insight.interceptor;

import com.coolskyai.coolpilot.insight.annotation.RequireRole;
import com.coolskyai.coolpilot.insight.utils.JwtUtil;
import com.coolskyai.coolpilot.insight.utils.TokenStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
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
            response.setStatus(401);
            response.getWriter().write("{\"code\":401,\"message\":\"未登录\"}");
            return false;
        }

        try {
            if (!tokenStore.exists(token)) {
                response.setStatus(401);
                response.getWriter().write("{\"code\":401,\"message\":\"登录已过期\"}");
                return false;
            }

            Long userId = jwtUtil.getUserId(token);
            Integer role = jwtUtil.getRole(token);

            RequireRole requireRole = method.getAnnotation(RequireRole.class);
            if (requireRole.role() > role) {
                response.setStatus(403);
                response.getWriter().write("{\"code\":403,\"message\":\"权限不足\"}");
                return false;
            }

            RequestContext.setUserId(userId);
            RequestContext.setRole(role);
            return true;
        } catch (Exception e) {
            response.setStatus(401);
            response.getWriter().write("{\"code\":401,\"message\":\"Token无效\"}");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        RequestContext.clear();
    }
}
