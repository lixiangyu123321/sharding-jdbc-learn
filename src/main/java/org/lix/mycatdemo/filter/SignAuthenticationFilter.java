package org.lix.mycatdemo.filter;


import com.alibaba.cloud.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.*;
import javax.servlet.FilterConfig;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import static org.lix.mycatdemo.filter.CommonError.INVALID_PARAM_USER_NOT_LOGIN;
import static org.lix.mycatdemo.filter.CommonError.LOGIN_STATE_EXPIRED;

@Component("mySignAuthenticationFilter")
public class SignAuthenticationFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(SignAuthenticationFilter.class);

    /**
     * Redis客户端
     */
    //private StringRedisTemplate stringRedisTemplate;

    /**
     * 有效期
     */
    private static long expireInMs = 5 * 60 * 1000L;

    /**
     * 签名算法密钥
     */
    private static String secretKey;

    /**
     * 签名算法相关实例
     */
    private static Mac mac;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException{
        try {
            //this.stringRedisTemplate = SpringContextUtil.getBean(StringRedisTemplate.class);

            //expireInMs = VivoConfigManager.getLong("blue.canvas.request.expire");
            expireInMs = Long.MAX_VALUE;
            secretKey = "lixiangyu";
            //secretKey = VivoConfigManager.getString("blue.canvas.request.sign.secret");

            if (StringUtils.isBlank(secretKey)) {
                throw new IllegalArgumentException("验签密钥配置为空：blue.canvas.request.sign.secret");
            }
            if (expireInMs <= 0) {
                throw new IllegalArgumentException("请求有效期配置非法：blue.canvas.request.expire，值为" + expireInMs);
            }
//            if (stringRedisTemplate == null) {
//                throw new RuntimeException("Spring上下文未找到StringRedisTemplate Bean");
//            }

            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8),
                    HMAC_SHA256_ALGORITHM
            );
            mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
            mac.init(secretKeySpec);

        } catch (Exception e) {
            ServletContext servletContext = filterConfig.getServletContext();
            WebApplicationContext webApplicationContext = WebApplicationContextUtils.getWebApplicationContext(servletContext);
            if (webApplicationContext instanceof ConfigurableApplicationContext) {
                ((ConfigurableApplicationContext) webApplicationContext).close();
            }

            System.exit(1);

            throw new ServletException("SignFilter初始化失败，应用已终止启动", e);
        }
    }

    /**
     * 验签：cookie中用户openId，有效期，防止重放，签名
     */
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain filterChain) throws IOException, ServletException {
        HttpServletResponse response = (HttpServletResponse) res;
        HttpServletRequest request = (HttpServletRequest) req;
        String requestURI = request.getRequestURI();
        String requestTime = request.getHeader("x-request-time");
        String nonce = request.getHeader("x-nonce");
        String signature = request.getHeader("x-signature");
        String[] openId = new String[1];
        Cookie[] cookies = request.getCookies();

        if(cookies != null){
            Arrays.stream(cookies)
                    .filter(cookie -> cookie.getName().equals("openid"))
                    .findFirst().ifPresent(cookie -> openId[0] = cookie.getValue());
        }
        if(openId[0] == null){
            // 未获得用户信息
            ResponseWriter.writeErrorMsg(response, INVALID_PARAM_USER_NOT_LOGIN);
            return;
        }

        try{
            // 有效期校验
            long time = Long.parseLong(requestTime);
            long now = System.currentTimeMillis();
            boolean isWithinExpire = Math.abs(now - time) <= expireInMs;
            if(!isWithinExpire){
                // 请求过期
                ResponseWriter.writeErrorMsg(response, LOGIN_STATE_EXPIRED);
                return;
            }

            //boolean isRepeat =  stringRedisTemplate.opsForValue().get(nonce) != null;
            boolean isRepeat = false;
            if(isRepeat){
                // 这里是请求重复，直接拒绝
                // TODO 请求重复，没有对应的状态码
                ResponseWriter.writeErrorMsg(response, LOGIN_STATE_EXPIRED);
                return;
            }
            //stringRedisTemplate.opsForValue().set(nonce, nonce, expireInMs, TimeUnit.MILLISECONDS);
            // 验签
            StringBuilder sb = new StringBuilder();
            String serverData = sb.append(requestURI).append(requestTime).append(nonce).append(openId[0]).toString();
            logger.info("服务端获取的所有数据:{}", serverData);
            if(!verifySign(serverData, secretKey, signature)){
                // 数据被篡改，签名异常
                // TODO 签名异常，没有具体的状态码
                ResponseWriter.writeErrorMsg(response, LOGIN_STATE_EXPIRED);
                return;
            }
        } catch (Exception e){
            // TODO 这里都是返回请求参数有误，通用验签失败状态码
            // 出现任何异常都算验签失败，拒绝请求
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        // 放行
        filterChain.doFilter(req, res);
    }

    @Override
    public void destroy() {

    }

    // 算法名称（固定写法）
    private static final String HMAC_SHA256_ALGORITHM = "HmacSHA256";

    /**
     * 生成HMAC-SHA256签名
     * @param data 原始数据
     * @param secretKey 共享对称密钥
     * @return base64算法转换的文本数据，便于传输
     */
    public String generateSign(String data, String secretKey){

        byte[] signBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

        String base64Sign = Base64.getEncoder().encodeToString(signBytes);
        return base64Sign;
    }

    /**
     * 验证签名
     * @param data 原始数据（服务端获取的请求数据）
     * @param secretKey 共享密钥
     * @param clientSign 客户端传来的签名串
     * @return true=签名合法（数据未篡改），false=签名非法
     */
    public boolean verifySign(String data, String secretKey, String clientSign) {
        try {
            String serverSign = generateSign(data, secretKey);
            return MessageDigest.isEqual(
                    serverSign.getBytes(StandardCharsets.UTF_8),
                    clientSign.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            // 任何异常都视为签名验证失败
            return false;
        }
    }
}
