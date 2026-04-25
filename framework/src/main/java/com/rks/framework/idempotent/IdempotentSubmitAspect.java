
package com.rks.framework.idempotent;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.google.gson.Gson;
import com.rks.framework.context.UserContext;
import com.rks.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 幂等提交切面 - 基于分布式锁的防重复提交实现
 *
 * <p>
 * IdempotentSubmitAspect 是 IdempotentSubmit 注解的实现切面。
 * 使用 Redisson 分布式锁确保在集群环境下的幂等性。
 * </p>
 *
 * <h2>工作流程</h2>
 * <ol>
 *   <li>获取方法上的 @IdempotentSubmit 注解</li>
 *   <li>根据注解配置构建分布式锁 Key</li>
 *   <li>尝试获取分布式锁（tryLock）</li>
 *   <li>获取成功：执行目标方法，finally 中释放锁</li>
 *   <li>获取失败：抛出 ClientException</li>
 * </ol>
 *
 * <h2>锁 Key 构建规则</h2>
 * <ul>
 *   <li>自定义 Key（SpEL）：{@code idempotent-submit:key:{spelValue}}</li>
 *   <li>默认 Key：{@code idempotent-submit:path:{servletPath}:currentUserId:{userId}:md5:{argsMd5}}</li>
 * </ul>
 *
 * <h2>依赖组件</h2>
 * <ul>
 *   <li>RedissonClient - 分布式锁客户端</li>
 *   <li>SpELUtil - SpEL 表达式解析工具</li>
 *   <li>UserContext - 获取当前用户 ID</li>
 * </ul>
 *
 * @see IdempotentSubmit
 * @see com.rks.framework.exception.ClientException
 */
@Aspect
@Component
@RequiredArgsConstructor
public final class IdempotentSubmitAspect {

    private final RedissonClient redissonClient;
    private final Gson gson = new Gson();

    /**
     * 增强方法标记 {@link IdempotentSubmit} 注解逻辑
     */
    @Around("@annotation(com.nageoffer.ai.ragent.framework.idempotent.IdempotentSubmit)")
    public Object idempotentSubmit(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1. 获取方法上的幂等提交注解配置
        IdempotentSubmit idempotentSubmit = getIdempotentSubmitAnnotation(joinPoint);
        // 2. 根据注解配置构建分布式锁的 Key
        String lockKey = buildLockKey(joinPoint, idempotentSubmit);
        // 3. 获取 Redisson 分布式锁实例
        RLock lock = redissonClient.getLock(lockKey);
        // 4. 尝试获取锁，获取锁失败意味着已经重复提交，直接抛出异常
        if (!lock.tryLock()) {
            throw new ClientException(idempotentSubmit.message());
        }
        Object result;
        try {
            // 5. 获取锁成功，执行目标方法的原逻辑
            result = joinPoint.proceed();
        } finally {
            // 6. finally 中释放锁，确保无论成功还是异常都要释放
            lock.unlock();
        }
        return result;
    }

    /**
     * 获取方法上的 IdempotentSubmit 注解
     *
     * <p>
     * 通过反射获取目标方法的声明，然后返回其上的注解。
     * 注意：这里获取的是声明的方法，而不是接口/父类的方法。
     * </p>
     *
     * @return 方法上的 IdempotentSubmit 注解
     */
    public static IdempotentSubmit getIdempotentSubmitAnnotation(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
        // 1. 获取方法签名（包含方法名和参数类型）
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        // 2. 获取目标类中声明的方法（使用接口方法的参数类型匹配）
        Method targetMethod = joinPoint.getTarget().getClass().getDeclaredMethod(methodSignature.getName(), methodSignature.getMethod().getParameterTypes());
        // 3. 返回方法上的 IdempotentSubmit 注解
        return targetMethod.getAnnotation(IdempotentSubmit.class);
    }

    /**
     * 获取当前请求的 Servlet 路径
     *
     * @return 当前线程上下文的请求 ServletPath
     */
    private String getServletPath() {
        // 1. 从 RequestContextHolder 获取当前请求的属性
        ServletRequestAttributes sra = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        // 2. 获取请求对象并返回其 ServletPath（请求路径，不含查询参数）
        return Objects.requireNonNull(sra).getRequest().getServletPath();
    }

    /**
     * 获取当前操作用户的 ID
     *
     * @return 当前登录用户的 ID，如果未登录则返回 null
     */
    private String getCurrentUserId() {
        return UserContext.getUserId();
    }

    /**
     * 计算方法参数的 MD5 哈希值
     *
     * <p>
     * 用于生成幂等 Key 的一部分，确保相同参数的请求生成相同的 MD5。
     * 步骤：
     * </p>
     * <ol>
     *   <li>将方法参数数组序列化为 JSON 字符串</li>
     *   <li>将 JSON 字符串转换为 UTF-8 字节数组</li>
     *   <li>计算 MD5 哈希值（32 位十六进制字符串）</li>
     * </ol>
     *
     * @return 参数的 MD5 哈希值
     */
    private String calcArgsMD5(ProceedingJoinPoint joinPoint) {
        // 1. 将方法参数数组序列化为 JSON
        // 2. 转换为 UTF-8 字节数组
        // 3. 计算 MD5 十六进制字符串
        return DigestUtil.md5Hex(gson.toJson(joinPoint.getArgs()).getBytes(StandardCharsets.UTF_8));
    }

    private String buildLockKey(ProceedingJoinPoint joinPoint, IdempotentSubmit idempotentSubmit) {
        // 1. 判断是否配置了自定义 SpEL 表达式 Key
        if (StrUtil.isNotBlank(idempotentSubmit.key())) {
            // 2. 使用 SpEL 表达式解析 Key 值（如 #userId）
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Object keyValue = SpELUtil.parseKey(idempotentSubmit.key(), signature.getMethod(), joinPoint.getArgs());
            return String.format("idempotent-submit:key:%s", keyValue);
        }
        // 3. 未配置自定义 Key，使用默认规则构建：
        //    路径 + 用户ID + 参数MD5 的组合确保唯一性
        return String.format(
                "idempotent-submit:path:%s:currentUserId:%s:md5:%s",
                getServletPath(),
                getCurrentUserId(),
                calcArgsMD5(joinPoint)
        );
    }
}
