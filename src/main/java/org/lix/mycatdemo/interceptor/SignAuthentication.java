package org.lix.mycatdemo.interceptor;

import com.alibaba.nacos.common.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.lix.mycatdemo.filter.ResponseWriter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.lix.mycatdemo.filter.CommonError.*;

@Slf4j
@Service
@RefreshScope
public class SignAuthentication implements InitializingBean {

    /**
     * Redis客户端
     */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 有效期
     */
    @Value("${signature.authentication.expireInMs:300000}")
    private static long expireInMs = 5 * 60 * 1000L;

    /**
     * 签名算法密钥
     */
    @Value("${signature.authentication.secretKey:lixiangyu}")
    private static String secretKey = "lixiangyu";

    /**
     * 签名算法相关实例
     */
    private static Mac mac;

    /**
     * 算法名称
     */
    private static final String HMAC_SHA256_ALGORITHM = "HmacSHA256";


    @Override
    public void afterPropertiesSet() throws Exception {
//        expireInMs = VivoConfigManager.getLong("blue.canvas.request.expire", expireInMs);
//        secretKey = VivoConfigManager.getString("blue.canvas.request.sign.secret", "blus-canvas-api");

        if (StringUtils.isBlank(secretKey)) {
            throw new IllegalArgumentException("验签密钥配置为空：blue.canvas.request.sign.secret");
        }
        if (expireInMs <= 0) {
            throw new IllegalArgumentException("请求有效期配置非法：blue.canvas.request.expire，值为" + expireInMs);
        }
        if (stringRedisTemplate == null) {
            throw new RuntimeException("Spring上下文未找到StringRedisTemplate Bean");
        }

        SecretKeySpec secretKeySpec = new SecretKeySpec(
                secretKey.getBytes(StandardCharsets.UTF_8),
                HMAC_SHA256_ALGORITHM
        );
        mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
        mac.init(secretKeySpec);
    }

    /**
     * 验签：用户openId，有效期，防止重放，签名
     */
    public boolean signAuthenticate(HttpServletRequest request, HttpServletResponse response) throws Exception {
//        String requestMethod = request.getMethod();
//        if("GET".equalsIgnoreCase(requestMethod)){
//            return true;
//        }
        String requestURI = request.getRequestURI();
        String requestTime = request.getHeader("x-request-time");
        String nonce = request.getHeader("x-nonce");
        String signature = request.getHeader("x-signature");

        if (StringUtils.isBlank(requestURI) || StringUtils.isBlank(requestTime) || StringUtils.isBlank(signature)) {
            ResponseWriter.writeErrorMsg(response, REQUEST_PARAM_ERROR);
            return false;
        }
        // TODO mock用户信息
//        if(Objects.isNull(user) || StringUtils.isBlank(user.getUserId())){
//            ResponseWriter.writeErrorMsg(response, INVALID_PARAM_USER_NOT_LOGIN);
//            return false;
//        }

        long time = Long.parseLong(requestTime);
        long now = System.currentTimeMillis();
        boolean isWithinExpire = Math.abs(now - time) <= expireInMs;
        if(!isWithinExpire){
            // 请求过期
            ResponseWriter.writeErrorMsg(response, REQUEST_STATE_EXPIRED);
            return false;
        }

        boolean isRepeat =  stringRedisTemplate.opsForValue().get(nonce) != null;
        if(isRepeat){
            // 这里是请求重复，直接拒绝
            ResponseWriter.writeErrorMsg(response, REQUEST_STATE_REPEATED);
            return false;
        }
        stringRedisTemplate.opsForValue().set(nonce, nonce, expireInMs, TimeUnit.MILLISECONDS);
        // 验签
        StringBuilder sb = new StringBuilder();
        String serverData = sb.append(requestURI).append(requestTime).append(nonce).append("lixiangyu").toString();
        if(!verifySign(serverData, signature)){
            // 数据被篡改，签名异常
            ResponseWriter.writeErrorMsg(response, REQUEST_PARAM_ERROR);
            return false;
        }

        return true;
    }

    /**
     * 生成HMAC-SHA256签名
     * @param data 原始数据
     * @return base64算法转换的文本数据，便于传输
     */
    private String generateSign(String data){
        byte[] signBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        String base64Sign = Base64.getEncoder().encodeToString(signBytes);
        return base64Sign;
    }

    /**
     * 验证签名
     * @param data 原始数据（服务端获取的请求数据）
     * @param clientSign 客户端传来的签名串
     * @return true=签名合法（数据未篡改），false=签名非法
     */
    private boolean verifySign(String data, String clientSign) {
        try {
            String serverSign = generateSign(data);
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

