
package com.rks.framework.config;


import com.rks.framework.web.GlobalExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Web 组件自动装配配置类
 *
 * <p>
 * WebAutoConfiguration 负责装配 Web 层的核心组件。
 * 通过 @Configuration 和 @Bean 注解，将组件注入 Spring 容器。
 * </p>
 *
 * <h2>配置内容</h2>
 * <ul>
 *   <li>GlobalExceptionHandler - 全局异常处理器 Bean</li>
 * </ul>
 *
 * <h2>设计背景</h2>
 * <p>
 * 将 GlobalExceptionHandler 放在 framework 模块中统一管理，
 * 确保所有使用 framework 的项目都能统一处理异常。
 * </p>
 *
 * @see com.rks.framework.web.GlobalExceptionHandler
 */
@Configuration
public class WebAutoConfiguration {

    /**
     * 构建全局异常拦截器组件 Bean
     */
    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
