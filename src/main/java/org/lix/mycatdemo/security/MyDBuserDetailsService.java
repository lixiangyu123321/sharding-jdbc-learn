package org.lix.mycatdemo.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 自定义实现的用户信息查询
 */
@Service
public class MyDBuserDetailsService implements UserDetailsService {

    /**
     * 注入密码编码器
     */
    @Resource
    private PasswordEncoder passwordEncoder;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

        // 这里应该从数据库中查到信息，这里直接mock了
        UserDetails userDetails = User.builder()
                .username("lixianghu")
                .password(passwordEncoder.encode("123456"))
                .roles("ADMIN")
                .build();

        return userDetails;
    }
}
