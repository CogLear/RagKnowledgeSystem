package com.rks.user.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rks.user.dao.entity.UserDO;

/**
 * 用户 Mapper 接口
 *
 * <p>
 * 基于 MyBatis-Plus 的用户数据访问层，
 * 提供 UserDO 实体的 CRUD 操作。
 * </p>
 *
 * @see com.rks.user.dao.entity.UserDO
 */
public interface UserMapper extends BaseMapper<UserDO> {
}
