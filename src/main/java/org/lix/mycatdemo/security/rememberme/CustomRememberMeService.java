package org.lix.mycatdemo.security.rememberme;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.RememberMeServices;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class CustomRememberMeService implements RememberMeServices {

    /**
     * 用户未携带有效登录凭证，但访问了需要认证的接口时，SpringSecurity会调用这个方法，尝试从请求中提取记住我的令牌
     * 职责:
     *  1.从request中解析出记住我的令牌（比如读取前端传来的remember-me Cookie）；
     *  2.校验令牌的合法性（比如令牌是否过期、签名是否正确、是否与数据库 / 缓存中的令牌匹配）；
     *  3.如果令牌合法，根据令牌关联的用户信息，构建并返回有效的Authentication认证对象，Spring Security 会基于这个对象完成自动登录；
     *  4.如果令牌非法 / 不存在 / 过期，直接返回null，Spring Security 会继续走正常的认证流程（比如跳转到登录页、返回未认证）。
     */
    @Override
    public Authentication autoLogin(HttpServletRequest request, HttpServletResponse response) {
        return null;
    }

    /**
     * 用户手动登录失败，springSecurity会调用这个方法
     *  1.清理请求 / 响应中与记住我相关的无效凭证（比如删除前端的remember-me Cookie、作废缓存 / 数据库中已存在的旧令牌）；
     *  2.防止无效的记住我令牌残留，避免后续自动登录时出现异常；
     *  3.该方法无返回值，仅做清理 / 失效操作即可。
     */
    @Override
    public void loginFail(HttpServletRequest request, HttpServletResponse response) {

    }

    /**
     * 登录成功时，生成并下发记住我的凭证
     *  1.先判断用户是否勾选了 “记住我”（从request中提取标识，比如request.getParameter("remember-me")）；
     *  2.如果勾选，生成唯一的记住我令牌（建议随机字符串 + 签名，防止伪造）；
     *  3.将令牌持久化存储（比如存数据库、Redis，关联用户 ID、过期时间）；
     *  4.将令牌下发给前端（通常通过Cookie写入响应，比如设置remember-me Cookie，指定过期时间、路径）；
     */
    @Override
    public void loginSuccess(HttpServletRequest request, HttpServletResponse response, Authentication successfulAuthentication) {
        Cookie rememberMeCookie = new Cookie("remember-me", "生成的令牌字符串");
        // 1. 禁止前端JS读取，防XSS
        rememberMeCookie.setHttpOnly(true);
        // 2. 仅HTTPS传输，生产环境必须开启（本地测试可暂时关闭）
        rememberMeCookie.setSecure(true);
        // 3. 限定Cookie的作用路径，仅在项目路径下生效，缩小作用域
        rememberMeCookie.setPath(request.getContextPath() + "/");
        // 4. 设置过期时间（比如7天，单位：秒），避免永久有效
        rememberMeCookie.setMaxAge(7 * 24 * 60 * 60);

        // 写入响应
        response.addCookie(rememberMeCookie);
    }
}
