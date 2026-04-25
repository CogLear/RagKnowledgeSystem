
package com.rks.framework.idempotent;
import com.rks.framework.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 幂等消费切面 - 基于 Redis 的防重复消费实现
 *
 * <p>
 * IdempotentConsumeAspect 是 IdempotentConsume 注解的实现切面。
 * 使用 Redis + Lua 脚本实现原子性的消费状态管理。
 * </p>
 *
 * <h2>工作流程</h2>
 * <ol>
 *   <li>解析 SpEL 表达式，构建唯一 Key</li>
 *   <li>使用 Lua 脚本原子性设置 Key 为"消费中"状态（SET NX PX）</li>
 *   <li>根据返回值判断：
 *     <ul>
 *       <li>返回"消费中"：消息正在被其他消费者处理，抛出异常</li>
 *       <li>返回"已消费"：消息已处理完成，跳过执行</li>
 *       <li>返回 null：获取锁成功，执行消费逻辑</li>
 *     </ul>
 *   </li>
 *   <li>消费成功后设置 Key 为"已消费"状态</li>
 *   <li>消费异常时删除 Key，允许后续重试</li>
 * </ol>
 *
 * <h2>Lua 脚本说明</h2>
 * <pre>
 * SET key value NX PX expire_time_ms
 * - NX：key 不存在时才设置
 * - PX：过期时间以毫秒为单位
 * - 返回 OK 表示设置成功，返回 nil 表示 key 已存在
 * </pre>
 *
 * <h2>依赖组件</h2>
 * <ul>
 *   <li>StringRedisTemplate - Redis 客户端</li>
 *   <li>SpELUtil - SpEL 表达式解析</li>
 * </ul>
 *
 * @see IdempotentConsume
 * @see IdempotentConsumeStatusEnum
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public final class IdempotentConsumeAspect {

    private final StringRedisTemplate stringRedisTemplate;

    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local value = ARGV[1]
            local expire_time_ms = ARGV[2]
            return redis.call('SET', key, value, 'NX', 'GET', 'PX', expire_time_ms)
            """;

    /**
     * 增强方法标记 {@link IdempotentConsume} 注解逻辑
     */
    @Around("@annotation(com.nageoffer.ai.ragent.framework.idempotent.IdempotentConsume)")
    public Object idempotentConsume(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1. 获取幂等消费注解配置
        IdempotentConsume idempotentConsume = getIdempotentConsumeAnnotation(joinPoint);
        // 2. 构建分布式锁的唯一 Key：前缀 + SpEL 表达式解析结果
        String uniqueKey = idempotentConsume.keyPrefix()
                + SpELUtil.parseKey(idempotentConsume.key(), ((MethodSignature) joinPoint.getSignature()).getMethod(), joinPoint.getArgs());
        // 3. 获取注解配置的超时时间
        long keyTimeoutSeconds = idempotentConsume.keyTimeout();

        // 4. 使用 Lua 脚本原子性地尝试设置 Key 为"消费中"状态
        //    - 如果 Key 不存在，设置成功并返回 null（获取锁成功）
        //    - 如果 Key 已存在，返回当前值
        String absentAndGet = stringRedisTemplate.execute(
                RedisScript.of(LUA_SCRIPT, String.class),
                List.of(uniqueKey),
                IdempotentConsumeStatusEnum.CONSUMING.getCode(),
                String.valueOf(TimeUnit.SECONDS.toMillis(keyTimeoutSeconds))
        );

        // 5. 判断获取锁的结果
        boolean errorFlag = IdempotentConsumeStatusEnum.isError(absentAndGet);
        if (errorFlag) {
            // 5a. 返回"消费中"状态：消息正被其他消费者处理，提示延迟重试
            log.warn("[{}] MQ repeated consumption, wait for delayed retry.", uniqueKey);
            throw new ServiceException(String.format("消息消费者幂等异常，幂等标识：%s", uniqueKey));
        }
        if (IdempotentConsumeStatusEnum.CONSUMED.getCode().equals(absentAndGet)) {
            // 5b. 返回"已消费"状态：消息已处理完成，直接跳过
            log.info("[{}] MQ consumption already completed, skip.", uniqueKey);
            return null;
        }

        // 6. absentAndGet 为 null，表示获取锁成功，执行消费逻辑
        try {
            Object result = joinPoint.proceed();
            // 7. 消费成功，标记 Key 为"已消费"状态
            stringRedisTemplate.opsForValue().set(
                    uniqueKey,
                    IdempotentConsumeStatusEnum.CONSUMED.getCode(),
                    keyTimeoutSeconds,
                    TimeUnit.SECONDS
            );
            return result;
        } catch (Throwable ex) {
            // 8. 消费异常，删除 Key，允许后续重试
            stringRedisTemplate.delete(uniqueKey);
            throw ex;
        }
    }

    /**
     * 获取方法上的 IdempotentConsume 注解
     *
     * @return 方法上的 IdempotentConsume 注解
     */
    public static IdempotentConsume getIdempotentConsumeAnnotation(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
        // 1. 获取方法签名
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        // 2. 获取目标类中声明的方法
        Method targetMethod = joinPoint.getTarget().getClass().getDeclaredMethod(methodSignature.getName(), methodSignature.getMethod().getParameterTypes());
        // 3. 返回方法上的 IdempotentConsume 注解
        return targetMethod.getAnnotation(IdempotentConsume.class);
    }
}
