
package com.rks.framework.distributedid;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import org.springframework.stereotype.Component;

/**
 * 自定义 ID 生成器 - 基于 Snowflake 算法的分布式 ID
 *
 * <p>
 * CustomIdentifierGenerator 实现了 MyBatis-Plus 的 IdentifierGenerator 接口，
 * 使用 Hutool 的 Snowflake 算法生成全局唯一的分布式 ID，替换 MyBatis-Plus 默认的 ID 生成策略。
 * </p>
 *
 * <h2>核心方法</h2>
 * <ul>
 *   <li>{@link #nextId(Object)} - 生成数值类型的 ID</li>
 *   <li>{@link #nextUUID(Object)} - 生成字符串类型的 ID</li>
 * </ul>
 *
 * <h2>设计背景</h2>
 * <p>
 * 默认情况下 MyBatis-Plus 使用数据库自增主键，
 * 在分布式环境下可能产生 ID 冲突。
 * 通过实现 IdentifierGenerator 接口，使用 Snowflake 算法生成全局唯一 ID。
 * </p>
 *
 * <h2>Snowflake 算法</h2>
 * <p>
 * Snowflake 算法生成的 ID 包含：
 * </p>
 * <ul>
 *   <li>时间戳 - 41 位</li>
 *   <li>数据中心 ID - 5 位</li>
 *   <li>工作机器 ID - 5 位</li>
 *   <li>序列号 - 12 位</li>
 * </ul>
 * <p>
 * 整体构成 64 位 long 类型 ID。
 * </p>
 *
 * @see com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator
 * @see cn.hutool.core.util.IdUtil
 */
@Component
public class CustomIdentifierGenerator implements IdentifierGenerator {

    @Override
    public Number nextId(Object entity) {
        // 调用 Hutool IdUtil 获取 Snowflake 生成的分布式唯一 ID（数值类型）
        return IdUtil.getSnowflakeNextId();
    }

    @Override
    public String nextUUID(Object entity) {
        // 调用 Hutool IdUtil 获取 Snowflake 生成的分布式唯一 ID（字符串类型）
        return IdUtil.getSnowflakeNextIdStr();
    }
}
