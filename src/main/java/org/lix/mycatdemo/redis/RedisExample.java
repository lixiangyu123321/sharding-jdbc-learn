package org.lix.mycatdemo.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis使用示例
 * 演示如何使用RedisTemplate和StringRedisTemplate进行基本操作
 * 
 * @author lix
 */
@Slf4j
@Component
public class RedisExample {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 应用启动后自动执行Redis连接测试
     */
    @PostConstruct
    public void testRedisConnection() {
        log.info("========== 开始测试Redis连接 ==========");
        try {
            // 测试1: 基本字符串操作
            testStringOperations();
            
            // 测试2: 对象操作
            testObjectOperations();
            
            // 测试3: 过期时间操作
            testExpireOperations();
            
            // 测试4: 列表操作
            testListOperations();
            
            // 测试5: 哈希操作
            testHashOperations();
            
            // 测试6: 集合操作
            testSetOperations();
            
            log.info("========== Redis连接测试完成，所有操作正常 ==========");
        } catch (Exception e) {
            log.error("Redis连接测试失败", e);
        }
    }

    /**
     * 测试1: 基本字符串操作
     */
    public void testStringOperations() {
        log.info("--- 测试1: 基本字符串操作 ---");
        
        String key = "test:string:key";
        String value = "hello-redis-cluster";
        
        // 写入
        stringRedisTemplate.opsForValue().set(key, value);
        log.info("写入数据: {} = {}", key, value);
        
        // 读取
        String result = stringRedisTemplate.opsForValue().get(key);
        log.info("读取数据: {} = {}", key, result);
        
        // 验证
        if (value.equals(result)) {
            log.info("✓ 字符串操作测试通过");
        } else {
            log.error("✗ 字符串操作测试失败: 期望值={}, 实际值={}", value, result);
        }
        
        // 删除
        stringRedisTemplate.delete(key);
        log.info("删除数据: {}", key);
    }

    /**
     * 测试2: 对象操作
     */
    public void testObjectOperations() {
        log.info("--- 测试2: 对象操作 ---");
        
        String key = "test:object:user";
        User user = new User("张三", 25, "zhangsan@example.com");
        
        // 写入对象
        redisTemplate.opsForValue().set(key, user);
        log.info("写入对象: {} = {}", key, user);
        
        // 读取对象
        User result = (User) redisTemplate.opsForValue().get(key);
        log.info("读取对象: {} = {}", key, result);
        
        // 验证
        if (result != null && user.getName().equals(result.getName())) {
            log.info("✓ 对象操作测试通过");
        } else {
            log.error("✗ 对象操作测试失败");
        }
        
        // 删除
        redisTemplate.delete(key);
        log.info("删除对象: {}", key);
    }

    /**
     * 测试3: 过期时间操作
     */
    public void testExpireOperations() {
        log.info("--- 测试3: 过期时间操作 ---");
        
        String key = "test:expire:key";
        String value = "expire-test-value";
        
        // 写入并设置5秒过期
        stringRedisTemplate.opsForValue().set(key, value, 5, TimeUnit.SECONDS);
        log.info("写入数据并设置5秒过期: {} = {}", key, value);
        
        // 立即读取（应该存在）
        String result1 = stringRedisTemplate.opsForValue().get(key);
        log.info("立即读取: {} = {}", key, result1);
        
        // 获取剩余过期时间
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        log.info("剩余过期时间: {} 秒", ttl);
        
        if (result1 != null && result1.equals(value)) {
            log.info("✓ 过期时间操作测试通过");
        } else {
            log.error("✗ 过期时间操作测试失败");
        }
    }

    /**
     * 测试4: 列表操作
     */
    public void testListOperations() {
        log.info("--- 测试4: 列表操作 ---");
        
        String key = "test:list:key";
        
        // 清空列表
        stringRedisTemplate.delete(key);
        
        // 从右侧推入元素
        stringRedisTemplate.opsForList().rightPush(key, "item1");
        stringRedisTemplate.opsForList().rightPush(key, "item2");
        stringRedisTemplate.opsForList().rightPush(key, "item3");
        log.info("推入3个元素到列表: {}", key);
        
        // 获取列表长度
        Long size = stringRedisTemplate.opsForList().size(key);
        log.info("列表长度: {}", size);
        
        // 获取所有元素
        java.util.List<String> list = stringRedisTemplate.opsForList().range(key, 0, -1);
        log.info("列表内容: {}", list);
        
        if (size != null && size == 3) {
            log.info("✓ 列表操作测试通过");
        } else {
            log.error("✗ 列表操作测试失败");
        }
        
        // 删除
        stringRedisTemplate.delete(key);
    }

    /**
     * 测试5: 哈希操作
     */
    public void testHashOperations() {
        log.info("--- 测试5: 哈希操作 ---");
        
        String key = "test:hash:user";
        String field1 = "name";
        String value1 = "李四";
        String field2 = "age";
        String value2 = "30";
        
        // 设置哈希字段
        stringRedisTemplate.opsForHash().put(key, field1, value1);
        stringRedisTemplate.opsForHash().put(key, field2, value2);
        log.info("设置哈希字段: {}[{}] = {}, {}[{}] = {}", key, field1, value1, key, field2, value2);
        
        // 获取哈希字段
        String result1 = (String) stringRedisTemplate.opsForHash().get(key, field1);
        String result2 = (String) stringRedisTemplate.opsForHash().get(key, field2);
        log.info("获取哈希字段: {}[{}] = {}, {}[{}] = {}", key, field1, result1, key, field2, result2);
        
        // 获取所有字段
        java.util.Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(key);
        log.info("哈希所有字段: {}", map);
        
        if (value1.equals(result1) && value2.equals(result2)) {
            log.info("✓ 哈希操作测试通过");
        } else {
            log.error("✗ 哈希操作测试失败");
        }
        
        // 删除
        stringRedisTemplate.delete(key);
    }

    /**
     * 测试6: 集合操作
     */
    public void testSetOperations() {
        log.info("--- 测试6: 集合操作 ---");
        
        String key = "test:set:key";
        
        // 清空集合
        stringRedisTemplate.delete(key);
        
        // 添加元素
        stringRedisTemplate.opsForSet().add(key, "member1", "member2", "member3");
        log.info("添加3个元素到集合: {}", key);
        
        // 获取集合大小
        Long size = stringRedisTemplate.opsForSet().size(key);
        log.info("集合大小: {}", size);
        
        // 获取所有成员
        Set<String> members = stringRedisTemplate.opsForSet().members(key);
        log.info("集合成员: {}", members);
        
        // 判断成员是否存在
        Boolean exists = stringRedisTemplate.opsForSet().isMember(key, "member1");
        log.info("member1是否存在: {}", exists);
        
        if (size != null && size == 3 && exists != null && exists) {
            log.info("✓ 集合操作测试通过");
        } else {
            log.error("✗ 集合操作测试失败");
        }
        
        // 删除
        stringRedisTemplate.delete(key);
    }

    /**
     * 测试用户类（用于对象序列化测试）
     */
    public static class User {
        private String name;
        private Integer age;
        private String email;

        public User() {
        }

        public User(String name, Integer age, String email) {
            this.name = name;
            this.age = age;
            this.email = email;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        @Override
        public String toString() {
            return "User{" +
                    "name='" + name + '\'' +
                    ", age=" + age +
                    ", email='" + email + '\'' +
                    '}';
        }
    }
}

