
package com.rks.framework.idempotent;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.ArrayUtil;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * SpEL 表达式解析工具 - 从方法参数中提取 SpEL 表达式的值
 *
 * <p>
 * SpELUtil 提供将 SpEL 表达式解析为实际值的能力。
 * 主要用于从方法参数中提取幂等 Key 或其他动态值。
 * </p>
 *
 * <h2>核心方法</h2>
 * <ul>
 *   <li>{@link #parseKey(String, Method, Object[])} - 解析 SpEL 表达式并返回值</li>
 *   <li>{@link #parse(String, Method, Object[])} - 执行 SpEL 表达式解析</li>
 * </ul>
 *
 * <h2>支持的表达式格式</h2>
 * <ul>
 *   <li>参数引用：{@code #userId}</li>
 *   <li>静态方法调用：{@code #user.getName()}</li>
 *   <li>直接返回字符串：如果表达式不包含 # 或 T(</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 解析 #userId
 * Object value = SpELUtil.parseKey("#userId", method, args);
 *
 * // 解析 #order.id
 * Object value = SpELUtil.parseKey("#order.id", method, args);
 * }</pre>
 *
 * @see org.springframework.expression.spel.standard.SpelExpressionParser
 */
public final class SpELUtil {

    private static final DefaultParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();
    private static final ExpressionParser EXPRESSION_PARSER = new SpelExpressionParser();

    /**
     * 校验并返回实际使用的 spEL 表达式
     *
     * @param spEl spEL 表达式
     * @return 实际使用的 spEL 表达式
     */
    public static Object parseKey(String spEl, Method method, Object[] contextObj) {
        // 1. 定义 SpEL 表达式的特征标记：# 表示参数引用，T( 表示类型引用
        List<String> spELFlag = ListUtil.of("#", "T(");
        // 2. 检查表达式是否包含 SpEL 特征标记
        Optional<String> optional = spELFlag.stream().filter(spEl::contains).findFirst();
        if (optional.isPresent()) {
            // 3. 包含特征标记，执行 SpEL 解析
            return parse(spEl, method, contextObj);
        }
        // 4. 不包含特征标记，直接返回原字符串
        return spEl;
    }

    /**
     * 转换参数为字符串
     *
     * @param spEl       spEl 表达式
     * @param contextObj 上下文对象
     * @return 解析的字符串值
     */
    public static Object parse(String spEl, Method method, Object[] contextObj) {
        // 1. 解析 SpEL 表达式字符串为 Expression 对象
        Expression exp = EXPRESSION_PARSER.parseExpression(spEl);
        // 2. 获取方法的参数名称列表（需要编译时保留参数名）
        String[] params = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
        // 3. 创建 SpEL 表达式求值上下文
        StandardEvaluationContext context = new StandardEvaluationContext();
        if (ArrayUtil.isNotEmpty(params)) {
            // 4. 将方法参数名和参数值设置到上下文变量中
            //    这样 SpEL 表达式才能通过 #paramName 访问实际参数值
            for (int len = 0; len < params.length; len++) {
                context.setVariable(params[len], contextObj[len]);
            }
        }
        // 5. 执行表达式求值，返回解析后的实际值
        return exp.getValue(context);
    }
}
