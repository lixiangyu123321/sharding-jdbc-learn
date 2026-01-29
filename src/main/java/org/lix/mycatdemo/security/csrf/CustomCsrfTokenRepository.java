package org.lix.mycatdemo.security.csrf;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 自定义csrf相关操作
 */
public class CustomCsrfTokenRepository implements CsrfTokenRepository {
    /**
     * 生成CSRF token令牌
     */
    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return null;
    }

    /**
     * 将CSRF token令牌传回前端，并存到服务端某个地方
     */
    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {

    }

    /**
     * 从请求中加载token
     */
    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        return null;
    }
}
