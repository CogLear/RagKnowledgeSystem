package com.rks.framework.config;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.rks.framework.database.MyMetaObjectHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
/**
 * 数据库持久层配置类 - MyBatis-Plus 核心配置
 *
 * <p>
 * DataBaseConfiguration 负责配置 MyBatis-Plus 的核心组件，
 * 包括分页插件和元数据自动填充处理器。
 * </p>
 *
 * <h2>配置内容</h2>
 * <ul>
 *   <li>MybatisPlusInterceptor - MyBatis-Plus 拦截器链，包含分页插件</li>
 *   <li>PaginationInnerInterceptor - MySQL 分页插件</li>
 *   <li>MyMetaObjectHandler - 元数据自动填充处理器</li>
 * </ul>
 *
 * <h2>分页插件说明</h2>
 * <p>
 * 配置 MySQL 的分页插件后，MyBatis-Plus 会自动对 SELECT 查询进行分页处理。
 * 只需要在方法参数中传入 Page 对象即可实现分页：
 * </p>
 * <pre>{@code
 * Page<User> page = new Page<>(current, size);
 * Page<User> result = userMapper.selectPage(page, null);
 * }</pre>
 *
 * @see com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor
 * @see com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor
 * @see com.rks.framework.database.MyMetaObjectHandler
 */
@Configuration
public class DataBaseConfiguration {

    /**
     * MyBatis-Plus MySQL 分页插件
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * MyBatis-Plus 源数据自动填充类
     */
    @Bean
    public MetaObjectHandler myMetaObjectHandler() {
        return new MyMetaObjectHandler();
    }
}
