package com.coolpilot.insight.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级权限校验（RBAC 权限码，如 {@code system:role:manage}）。
 *
 * <p>与 {@link RequireRole} 的区别：后者按"角色等级"粗粒度放行，
 * 本注解按具体权限点判定，可表达"审核员能看审计但不能改配置"这类组合。
 * 过渡期两者并存，新接口优先用本注解。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    String value();
}
