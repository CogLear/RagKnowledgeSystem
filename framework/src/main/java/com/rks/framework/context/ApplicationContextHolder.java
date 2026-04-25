package com.rks.framework.context;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import java.lang.annotation.Annotation;
import java.util.Map;

/**
 * Spring 应用上下文持有者 - 非 Spring 管理类获取 Bean 的工具
 *
 * <p>
 * ApplicationContextHolder 用于在非 Spring 管理的类中获取 Spring 容器中的 Bean 实例。
 * 通过实现 ApplicationContextAware 接口，在 Spring 容器启动时自动注入 ApplicationContext。
 * </p>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li>根据类型获取 Bean</li>
 *   <li>根据名称获取 Bean</li>
 *   <li>根据名称和类型获取 Bean</li>
 *   <li>获取同类型的所有 Bean</li>
 *   <li>查找 Bean 上的注解</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <p>
 * 在以下场景中需要主动获取 Spring Bean：
 * </p>
 * <ul>
 *   <li>工具类中需要使用 Spring Bean</li>
 *   <li>静态方法中需要获取 Bean</li>
 *   <li>第三方框架集成时需要获取 Bean</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 获取 Bean
 * MyService myService = ApplicationContextHolder.getBean(MyService.class);
 *
 * // 获取多个同类型 Bean
 * Map<String, MyService> services = ApplicationContextHolder.getBeansOfType(MyService.class);
 * }</pre>
 *
 * <h2>注意事项</h2>
 * <ul>
 *   <li>ApplicationContextHolder 本身是一个 @Component Bean</li>
 *   <li>静态方法的 CONTEXT 引用由 setApplicationContext 注入</li>
 *   <li>在 Spring 容器完全初始化后才能正常使用</li>
 * </ul>
 *
 * @see org.springframework.context.ApplicationContext
 */
@Component
public class ApplicationContextHolder implements ApplicationContextAware {

    private static ApplicationContext CONTEXT;

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        ApplicationContextHolder.CONTEXT = applicationContext;
    }

    /**
     * 根据类型获取 Bean
     */
    public static <T> T getBean(Class<T> clazz) {
        return CONTEXT.getBean(clazz);
    }

    /**
     * 根据名称获取 Bean
     */
    public static Object getBean(String name) {
        return CONTEXT.getBean(name);
    }

    /**
     * 根据名称和类型获取 Bean
     */
    public static <T> T getBean(String name, Class<T> clazz) {
        return CONTEXT.getBean(name, clazz);
    }

    /**
     * 根据类型获取同类型的所有 Bean
     */
    public static <T> Map<String, T> getBeansOfType(Class<T> clazz) {
        return CONTEXT.getBeansOfType(clazz);
    }

    /**
     * 查找 Bean 上的注解
     */
    public static <A extends Annotation> A findAnnotationOnBean(String beanName, Class<A> annotationType) {
        return CONTEXT.findAnnotationOnBean(beanName, annotationType);
    }

    /**
     * 获取 ApplicationContext
     */
    public static ApplicationContext getInstance() {
        return CONTEXT;
    }
}
