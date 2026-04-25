package com.rks.framework.database;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;

import java.util.Date;

/**
 * MyBatis-Plus 元数据自动填充处理器 - 自动化公共字段填充
 *
 * <p>
 * MyMetaObjectHandler 实现了 MyBatis-Plus 的 MetaObjectHandler 接口，
 * 用于在插入和更新操作时自动填充公共字段，如创建时间、更新时间、删除标记等。
 * </p>
 *
 * <h2>插入操作自动填充</h2>
 * <ul>
 *   <li>createTime - 创建时间，默认为当前时间</li>
 *   <li>updateTime - 更新时间，默认为当前时间</li>
 *   <li>deleted - 删除标记，默认为 0（未删除）</li>
 * </ul>
 *
 * <h2>更新操作自动填充</h2>
 * <ul>
 *   <li>updateTime - 更新时间，自动更新为当前时间</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <p>
 * 在实体类中配置 @TableField 注解的 fill 属性：
 * </p>
 * <pre>{@code
 * @Data
 * public class User {
 *     @TableField(fill = FieldFill.INSERT)
 *     private Date createTime;
 *
 *     @TableField(fill = FieldFill.INSERT_UPDATE)
 *     private Date updateTime;
 *
 *     @TableField(fill = FieldFill.INSERT)
 *     private Integer deleted;
 * }
 * }</pre>
 *
 * @see com.baomidou.mybatisplus.core.handlers.MetaObjectHandler
 */
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        // 1. 填充创建时间：首次插入时设置为当前时间
        strictInsertFill(metaObject, "createTime", Date::new, Date.class);
        // 2. 填充更新时间：首次插入时设置为当前时间
        strictInsertFill(metaObject, "updateTime", Date::new, Date.class);
        // 3. 填充删除标记：首次插入时设置为 0（未删除）
        strictInsertFill(metaObject, "deleted", () -> 0, Integer.class);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // 更新时只填充更新时间字段，自动更新为当前时间
        strictUpdateFill(metaObject, "updateTime", Date::new, Date.class);
    }
}