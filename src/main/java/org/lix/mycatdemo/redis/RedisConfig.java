package org.lix.mycatdemo.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis配置类
 * 配置RedisTemplate和StringRedisTemplate，支持Redis集群模式
 * 
 * 注意：LettuceConnectionFactory会由Spring Boot自动配置（从application.yaml读取spring.redis.cluster配置）
 * 这里只需要配置RedisTemplate和StringRedisTemplate的序列化方式
 * 
 * @author lix
 */
@Slf4j
@Configuration
public class RedisConfig {

    /**
     * 配置RedisTemplate（支持对象序列化）
     * 使用GenericJackson2JsonRedisSerializer进行序列化
     * GenericJackson2JsonRedisSerializer会在JSON中添加类型信息（@class字段），支持正确的反序列化
     * 
     * @param connectionFactory Spring Boot自动配置的LettuceConnectionFactory
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(@Autowired LettuceConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // 使用GenericJackson2JsonRedisSerializer来序列化和反序列化redis的value值
        // GenericJackson2JsonRedisSerializer会在JSON中添加类型信息，支持正确的反序列化
        GenericJackson2JsonRedisSerializer jsonRedisSerializer = new GenericJackson2JsonRedisSerializer();

        // 使用StringRedisSerializer来序列化和反序列化redis的key值
        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();

        // key采用String的序列化方式
        template.setKeySerializer(stringRedisSerializer);
        // hash的key也采用String的序列化方式
        template.setHashKeySerializer(stringRedisSerializer);
        // value序列化方式采用jackson
        template.setValueSerializer(jsonRedisSerializer);
        // hash的value序列化方式采用jackson
        template.setHashValueSerializer(jsonRedisSerializer);

        template.afterPropertiesSet();
        log.info("RedisTemplate初始化完成（支持对象序列化）");
        return template;
    }

    /**
     * 配置StringRedisTemplate（只支持字符串操作）
     * 性能更好，适合简单的字符串操作
     * 
     * @param connectionFactory Spring Boot自动配置的LettuceConnectionFactory
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(@Autowired LettuceConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        log.info("StringRedisTemplate初始化完成（字符串操作）");
        return template;
    }
}

