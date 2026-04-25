
package com.rks.rag.aop;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.rks.framework.context.UserContext;
import com.rks.framework.trace.RagTraceContext;
import com.rks.rag.config.RagTraceProperties;
import com.rks.rag.dao.entity.RagTraceRunDO;
import com.rks.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Date;

/**
 * SSE 全局限流切面 - 避免业务代码侵入
 *
 * <p>
 * ChatRateLimitAspect 是 RAG 流式对话的全局限流切面，
 * 通过拦截带有 @ChatRateLimit 注解的方法，实现对话请求的队列管理。
 * </p>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>限流控制</b> - 通过 ChatQueueLimiter 控制并发对话数量</li>
 *   <li><b>链路追踪</b> - 在限流切面中嵌入 RagTrace 记录</li>
 *   <li><b>异常处理</b> - 统一处理执行过程中的异常</li>
 * </ul>
 *
 * <h2>拦截规则</h2>
 * <ul>
 *   <li>方法必须标注 @ChatRateLimit 注解</li>
 *   <li>第四个参数必须为 SseEmitter 类型</li>
 *   <li>第一个参数为问题内容，第二个参数为会话ID</li>
 * </ul>
 *
 * <h2>执行流程</h2>
 * <ol>
 *   <li><b>参数校验</b> - 验证参数是否包含 SseEmitter</li>
 *   <li><b>会话ID处理</b> - 为空则生成新的雪花ID</li>
 *   <li><b>限流入队</b> - 将请求加入限流队列</li>
 *   <li><b>异步执行</b> - 队列触发后执行实际方法</li>
 *   <li><b>链路记录</b> - 记录方法执行的开始/结束/异常</li>
 * </ol>
 *
 * @see ChatRateLimit
 * @see ChatQueueLimiter
 * @see RagTraceRecordService
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ChatRateLimitAspect {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_ERROR = "ERROR";

    private final ChatQueueLimiter chatQueueLimiter;
    private final RagTraceProperties ragTraceProperties;
    private final RagTraceRecordService traceRecordService;

    /**
     * 限流拦截方法
     *
     * <p>
     * 拦截带有 @ChatRateLimit 注解的方法，实现全局限流控制。
     * 将请求加入限流队列，由队列触发实际的方法执行。
     * </p>
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li><b>参数校验</b> - 验证第四个参数是否为 SseEmitter</li>
     *   <li><b>提取参数</b> - 从方法参数中获取问题内容和会话ID</li>
     *   <li><b>会话ID处理</b> - 为空的会话ID生成雪花ID</li>
     *   <li><b>限流入队</b> - 将请求加入限流队列等待执行</li>
     *   <li><b>返回空结果</b> - 方法立即返回，异步执行实际逻辑</li>
     * </ol>
     *
     * @param joinPoint AOP 连接点
     * @return 空对象（实际逻辑异步执行）
     * @throws Throwable 参数校验失败时抛出
     */
    @Around("@annotation(com.rks.rag.aop.ChatRateLimit)")
    public Object limitStreamChat(ProceedingJoinPoint joinPoint) throws Throwable {
        // ========== 步骤1：参数校验 ==========
        // 验证方法参数是否符合预期（问题、会话ID、SSE发射器）
        Object[] args = joinPoint.getArgs();
        if (args == null || args.length < 4 || !(args[3] instanceof SseEmitter emitter)) {
            return joinPoint.proceed();
        }

        // ========== 步骤2：提取参数 ==========
        String question = args[0] instanceof String q ? q : "";
        String conversationId = args[1] instanceof String cid ? cid : null;

        // ========== 步骤3：会话ID处理 ==========
        // 为空的会话ID生成雪花ID，确保每次对话有唯一标识
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        args[1] = actualConversationId;

        // 获取目标对象和方法签名
        Object target = joinPoint.getTarget();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // ========== 步骤4：限流入队 ==========
        // 将请求加入限流队列，队列会控制并发数并在可执行时回调
        chatQueueLimiter.enqueue(question, actualConversationId, emitter, () -> {
            invokeWithTrace(method, target, args, question, actualConversationId, emitter);
        });
        return null;
    }

    /**
     * 异步执行方法并记录链路追踪
     *
     * <p>
     * 在限流队列触发时调用此方法执行实际的业务逻辑。
     * 如果链路追踪启用，则记录方法执行的开始、结束或异常。
     * </p>
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li><b>开关检查</b> - 如果链路追踪未启用，直接执行方法</li>
     *   <li><b>构建Run记录</b> - 创建 RagTraceRunDO 记录</li>
     *   <li><b>设置上下文</b> - 将 traceId 和 taskId 存入 ThreadLocal</li>
     *   <li><b>执行方法</b> - 通过反射调用目标方法</li>
     *   <li><b>记录完成</b> - 成功或失败时调用 finishRun</li>
     *   <li><b>清理上下文</b> - finally 中清理 ThreadLocal</li>
     * </ol>
     *
     * @param method          目标方法
     * @param target          目标对象
     * @param args            方法参数
     * @param question        问题内容（用于日志）
     * @param conversationId  会话ID
     * @param emitter         SSE发射器
     */
    private void invokeWithTrace(Method method,
                                 Object target,
                                 Object[] args,
                                 String question,
                                 String conversationId,
                                 SseEmitter emitter) {
        if (!ragTraceProperties.isEnabled()) {
            invokeTarget(method, target, args, emitter);
            return;
        }

        String traceId = IdUtil.getSnowflakeNextIdStr();
        String taskId = IdUtil.getSnowflakeNextIdStr();
        long startMillis = System.currentTimeMillis();
        traceRecordService.startRun(RagTraceRunDO.builder()
                .traceId(traceId)
                .traceName("rag-stream-chat")
                .entryMethod(method.getDeclaringClass().getName() + "#" + method.getName())
                .conversationId(conversationId)
                .taskId(taskId)
                .userId(UserContext.getUserId())
                .status(STATUS_RUNNING)
                .startTime(new Date())
                .extraData(StrUtil.format("{\"questionLength\":{}}", StrUtil.length(question)))
                .build());

        RagTraceContext.setTraceId(traceId);
        RagTraceContext.setTaskId(taskId);
        try {
            method.invoke(target, args);
            traceRecordService.finishRun(
                    traceId,
                    STATUS_SUCCESS,
                    null,
                    new Date(),
                    System.currentTimeMillis() - startMillis
            );
        } catch (Throwable ex) {
            Throwable cause = unwrap(ex);
            traceRecordService.finishRun(
                    traceId,
                    STATUS_ERROR,
                    truncateError(cause),
                    new Date(),
                    System.currentTimeMillis() - startMillis
            );
            log.warn("执行流式对话失败", cause);
            emitter.completeWithError(cause);
        } finally {
            RagTraceContext.clear();
        }
    }

    private void invokeTarget(Method method, Object target, Object[] args, SseEmitter emitter) {
        try {
            method.invoke(target, args);
        } catch (Throwable ex) {
            Throwable cause = unwrap(ex);
            log.warn("执行流式对话失败", cause);
            emitter.completeWithError(cause);
        }
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocationTargetException
                && invocationTargetException.getTargetException() != null) {
            return invocationTargetException.getTargetException();
        }
        return throwable;
    }

    private String truncateError(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        String message = throwable.getClass().getSimpleName() + ": " + StrUtil.blankToDefault(throwable.getMessage(), "");
        if (message.length() <= ragTraceProperties.getMaxErrorLength()) {
            return message;
        }
        return message.substring(0, ragTraceProperties.getMaxErrorLength());
    }
}
