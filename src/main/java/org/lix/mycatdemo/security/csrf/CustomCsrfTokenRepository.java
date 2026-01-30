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
     * 将CSRF token持久化存储
     */
    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {

    }

    /**
     * 从仓库中加载csrfToken
     */
    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        return null;
    }
}
