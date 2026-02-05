package org.lix.provider.dao.mapper;

import org.lix.provider.dao.entity.User;
import tk.mybatis.mapper.common.Mapper;

/**
 * 用户 Mapper 接口
 */
public interface UserMapper extends Mapper<User> {
    
    /**
     * 根据用户名查询用户
     * @param userName 用户名
     * @return 用户信息
     */
    User selectByUserName(String userName);
}

