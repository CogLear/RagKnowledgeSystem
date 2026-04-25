
package com.rks.ingestion.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rks.ingestion.domain.context.IngestionContext;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 条件评估器
 *
 * <p>
 * ConditionEvaluator 负责根据 IngestionContext 上下文和 JsonNode 格式的条件配置
 * 来评估条件是否满足。它支持多种条件表达方式，包括 SpEL 表达式、布尔逻辑
 * （all/any/not）以及字段规则评估。
 * </p>
 *
 * <h2>支持的条件类型</h2>
 * <ul>
 *   <li><b>空值/null</b> - 返回 true（条件满足）</li>
 *   <li><b>布尔值</b> - 直接返回布尔结果</li>
 *   <li><b>文本/SpEL</b> - 作为 SpEL 表达式求值</li>
 *   <li><b>对象-all</b> - 所有子条件都满足（AND）</li>
 *   <li><b>对象-any</b> - 任一子条件满足（OR）</li>
 *   <li><b>对象-not</b> - 条件取反（NOT）</li>
 *   <li><b>对象-field</b> - 字段规则评估</li>
 * </ul>
 *
 * <h2>字段规则支持的操作符</h2>
 * <table border="1">
 *   <tr><th>操作符</th><th>说明</th><th>示例</th></tr>
 *   <tr><td>eq</td><td>相等比较（默认）</td><td>{"field": "status", "operator": "eq", "value": "active"}</td></tr>
 *   <tr><td>ne</td><td>不相等</td><td>{"field": "status", "operator": "ne", "value": "deleted"}</td></tr>
 *   <tr><td>gt/gte/lt/lte</td><td>数值比较</td><td>{"field": "count", "operator": "gt", "value": 10}</td></tr>
 *   <tr><td>in</td><td>包含于列表</td><td>{"field": "type", "operator": "in", "value": ["a", "b"]}</td></tr>
 *   <tr><td>contains</td><td>字符串/列表包含</td><td>{"field": "tags", "operator": "contains", "value": "important"}</td></tr>
 *   <tr><td>regex</td><td>正则表达式匹配</td><td>{"field": "name", "operator": "regex", "value": "^user.*"}</td></tr>
 *   <tr><td>exists</td><td>字段存在</td><td>{"field": "email", "operator": "exists"}</td></tr>
 *   <tr><td>not_exists</td><td>字段不存在</td><td>{"field": "deletedAt", "operator": "not_exists"}</td></tr>
 * </table>
 *
 * @see IngestionContext
 * @see JsonNode
 */
@Component
public class ConditionEvaluator {

    private final ObjectMapper objectMapper;
    private final ExpressionParser parser = new SpelExpressionParser();

    public ConditionEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 评估条件是否满足
     *
     * <p>
     * 根据 condition 的结构类型，分发到不同的评估策略：
     * 空值返回 true，布尔值直接返回，文本作为 SpEL 求值，
     * 对象根据其属性（all/any/not/field）选择对应的评估逻辑。
     * </p>
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li><b>空值检查</b> - condition 为 null 或 NullNode 时返回 true</li>
     *   <li><b>布尔类型</b> - 直接返回 asBoolean()</li>
     *   <li><b>文本类型</b> - 作为 SpEL 表达式求值</li>
     *   <li><b>对象类型</b> - 根据属性分发到 all/any/not/field 处理</li>
     *   <li><b>Fallback</b> - 无法识别时返回 true</li>
     * </ol>
     *
     * 【读取】
     * <table border="1">
     *   <tr><th>参数</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>context</td><td>IngestionContext</td><td>摄入上下文，包含文档处理的所有中间状态</td></tr>
     *   <tr><td>condition</td><td>JsonNode</td><td>条件配置节点</td></tr>
     * </table>
     *
     * 【输出】
     * <table border="1">
     *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>result</td><td>boolean</td><td>true 表示条件满足，false 表示不满足</td></tr>
     * </table>
     *
     * @param context   摄入上下文
     * @param condition 条件配置
     * @return 条件是否满足
     */
    public boolean evaluate(IngestionContext context, JsonNode condition) {
        if (condition == null || condition.isNull()) {
            return true;
        }
        if (condition.isBoolean()) {
            return condition.asBoolean();
        }
        if (condition.isTextual()) {
            return evalSpel(context, condition.asText());
        }
        if (condition.isObject()) {
            if (condition.has("all")) {
                return evalAll(context, condition.get("all"));
            }
            if (condition.has("any")) {
                return evalAny(context, condition.get("any"));
            }
            if (condition.has("not")) {
                return !evaluate(context, condition.get("not"));
            }
            if (condition.has("field")) {
                return evalRule(context, condition);
            }
        }
        return true;
    }

    /**
     * 评估所有条件（AND 逻辑）
     *
     * <p>
     * 遍历数组中的所有条件项，只有当所有项都满足时返回 true。
     * 如果节点为空或不是数组类型，返回 true（空条件视为满足）。
     * 短路求值：遇到第一个不满足的条件立即返回 false。
     * </p>
     *
     * 【读取】
     * <table border="1">
     *   <tr><th>参数</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>context</td><td>IngestionContext</td><td>摄入上下文</td></tr>
     *   <tr><td>node</td><td>JsonNode</td><td>条件数组节点，包含 all 属性</td></tr>
     * </table>
     *
     * 【输出】
     * <table border="1">
     *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>result</td><td>boolean</td><td>所有条件都满足返回 true</td></tr>
     * </table>
     *
     * @param context 摄入上下文
     * @param node    条件数组节点
     * @return 所有条件是否都满足
     */
    private boolean evalAll(IngestionContext context, JsonNode node) {
        if (node == null || !node.isArray()) {
            return true;
        }
        for (JsonNode item : node) {
            if (!evaluate(context, item)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 评估任一条件（OR 逻辑）
     *
     * <p>
     * 遍历数组中的所有条件项，遇到任意一项满足时立即返回 true。
     * 如果节点为空或不是数组类型，返回 true（空条件视为满足）。
     * 短路求值：遇到第一个满足的条件立即返回 true。
     * </p>
     *
     * 【读取】
     * <table border="1">
     *   <tr><th>参数</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>context</td><td>IngestionContext</td><td>摄入上下文</td></tr>
     *   <tr><td>node</td><td>JsonNode</td><td>条件数组节点，包含 any 属性</td></tr>
     * </table>
     *
     * 【输出】
     * <table border="1">
     *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>result</td><td>boolean</td><td>任一条件满足返回 true</td></tr>
     * </table>
     *
     * @param context 摄入上下文
     * @param node    条件数组节点
     * @return 任一条件是否满足
     */
    private boolean evalAny(IngestionContext context, JsonNode node) {
        if (node == null || !node.isArray()) {
            return true;
        }
        for (JsonNode item : node) {
            if (evaluate(context, item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 评估字段规则条件
     *
     * <p>
     * 根据 field 规则评估条件是否满足。规则格式为：
     * {"field": "路径", "operator": "操作符", "value": "期望值"}
     * 使用 Spring 的 BeanWrapper 从 context 中读取字段值，并与期望值进行比较。
     * </p>
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li><b>字段解析</b> - 从 node.path("field") 获取字段路径</li>
     *   <li><b>操作符获取</b> - 从 node.path("operator") 获取操作符，默认为 "eq"</li>
     *   <li><b>期望值获取</b> - 从 node.get("value") 获取期望值</li>
     *   <li><b>字段读取</b> - 使用 BeanWrapper 从 context 读取字段值</li>
     *   <li><b>类型转换</b> - 将 valueNode 转换为 Java 对象</li>
     *   <li><b>比较执行</b> - 根据操作符执行相应的比较逻辑</li>
     * </ol>
     *
     * 【读取】
     * <table border="1">
     *   <tr><th>参数</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>context</td><td>IngestionContext</td><td>摄入上下文</td></tr>
     *   <tr><td>node</td><td>JsonNode</td><td>field 规则节点</td></tr>
     * </table>
     *
     * 【输出】
     * <table border="1">
     *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>result</td><td>boolean</td><td>字段条件是否满足</td></tr>
     * </table>
     *
     * @param context 摄入上下文
     * @param node    field 规则节点
     * @return 字段规则是否满足
     */
    private boolean evalRule(IngestionContext context, JsonNode node) {
        String field = node.path("field").asText(null);
        if (!StringUtils.hasText(field)) {
            return true;
        }
        String operator = node.path("operator").asText("eq");
        JsonNode valueNode = node.get("value");
        Object left = readField(context, field);
        Object right = valueNode == null ? null : objectMapper.convertValue(valueNode, Object.class);
        return compare(left, right, operator);
    }

    /**
     * 从上下文中读取字段值
     *
     * <p>
     * 使用 Spring 的 BeanWrapper 从 IngestionContext 中按照属性路径读取字段值。
     * 支持嵌套属性读取，如 "source.type"。读取失败时返回 null。
     * </p>
     *
     * 【读取】
     * <table border="1">
     *   <tr><th>参数</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>context</td><td>IngestionContext</td><td>摄入上下文</td></tr>
     *   <tr><td>path</td><td>String</td><td>属性路径，支持嵌套路径</td></tr>
     * </table>
     *
     * 【输出】
     * <table border="1">
     *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>result</td><td>Object</td><td>字段值，读取失败时返回 null</td></tr>
     * </table>
     *
     * @param context 摄入上下文
     * @param path    属性路径
     * @return 字段值
     */
    private Object readField(IngestionContext context, String path) {
        try {
            BeanWrapperImpl wrapper = new BeanWrapperImpl(context);
            return wrapper.getPropertyValue(path);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 根据操作符比较两个值
     *
     * <p>
     * 将 left 和 right 按照 operator 指定的规则进行比较。
     * 支持的操作符包括：eq（相等）、ne（不等）、gt/gte/lt/lte（数值比较）、
     * in（包含）、contains（包含检查）、regex（正则）、exists/not_exists（存在性）。
     * </p>
     *
     * <h3>操作符映射</h3>
     * <table border="1">
     *   <tr><th>操作符</th><th>实现</th></tr>
     *   <tr><td>eq/default</td><td>Objects.equals(normalize(left), normalize(right))</td></tr>
     *   <tr><td>ne</td><td>!Objects.equals(normalize(left), normalize(right))</td></tr>
     *   <tr><td>in</td><td>调用 in(left, right)</td></tr>
     *   <tr><td>contains</td><td>调用 contains(left, right)</td></tr>
     *   <tr><td>regex</td><td>调用 regex(left, right)</td></tr>
     *   <tr><td>gt/gte/lt/lte</td><td>数值比较</td></tr>
     *   <tr><td>exists</td><td>left != null</td></tr>
     *   <tr><td>not_exists</td><td>left == null</td></tr>
     * </table>
     *
     * 【读取】
     * <table border="1">
     *   <tr><th>参数</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>left</td><td>Object</td><td>左操作数（从 context 读取的字段值）</td></tr>
     *   <tr><td>right</td><td>Object</td><td>右操作数（期望值）</td></tr>
     *   <tr><td>operator</td><td>String</td><td>比较操作符</td></tr>
     * </table>
     *
     * 【输出】
     * <table border="1">
     *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>result</td><td>boolean</td><td>比较结果</td></tr>
     * </table>
     *
     * @param left     左操作数
     * @param right    右操作数
     * @param operator 比较操作符
     * @return 比较结果
     */
    private boolean compare(Object left, Object right, String operator) {
        return switch (operator.toLowerCase()) {
            case "ne" -> !Objects.equals(normalize(left), normalize(right));
            case "in" -> in(left, right);
            case "contains" -> contains(left, right);
            case "regex" -> regex(left, right);
            case "gt" -> compareNumber(left, right) > 0;
            case "gte" -> compareNumber(left, right) >= 0;
            case "lt" -> compareNumber(left, right) < 0;
            case "lte" -> compareNumber(left, right) <= 0;
            case "exists" -> left != null;
            case "not_exists" -> left == null;
            default -> Objects.equals(normalize(left), normalize(right));
        };
    }

    /**
     * 检查左值是否在右列表中，或右值是否在左列表中
     *
     * <p>
     * in 操作符的双向包含检查：
     * - 如果 right 是 List，则检查 left 是否在列表中
     * - 如果 left 是 List，则检查 right 是否在列表中
     * - 否则退化为普通的相等比较
     * </p>
     *
     * 【读取】
     * <table border="1">
     *   <tr><th>参数</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>left</td><td>Object</td><td>左操作数</td></tr>
     *   <tr><td>right</td><td>Object</td><td>右操作数</td></tr>
     * </table>
     *
     * 【输出】
     * <table border="1">
     *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>result</td><td>boolean</td><td>包含关系成立返回 true</td></tr>
     * </table>
     *
     * @param left  左操作数
     * @param right 右操作数
     * @return 是否存在包含关系
     */
    private boolean in(Object left, Object right) {
        if (right instanceof List<?> list) {
            return list.contains(left);
        }
        if (left instanceof List<?> list) {
            return list.contains(right);
        }
        return Objects.equals(normalize(left), normalize(right));
    }

    /**
     * 检查左值是否包含右值
     *
     * <p>
     * contains 操作符的包含检查：
     * - 如果 left 是 String，检查其是否包含 right.toString()
     * - 如果 left 是 List，检查其是否包含 right
     * - 其他情况返回 false
     * </p>
     *
     * 【读取】
     * <table border="1">
     *   <tr><th>参数</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>left</td><td>Object</td><td>被包含的容器（String 或 List）</td></tr>
     *   <tr><td>right</td><td>Object</td><td>要检查的元素或子串</td></tr>
     * </table>
     *
     * 【输出】
     * <table border="1">
     *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>result</td><td>boolean</td><td>包含关系成立返回 true</td></tr>
     * </table>
     *
     * @param left  被包含的容器
     * @param right 要检查的元素或子串
     * @return 是否包含
     */
    private boolean contains(Object left, Object right) {
        if (left == null || right == null) {
            return false;
        }
        if (left instanceof String ls) {
            return ls.contains(String.valueOf(right));
        }
        if (left instanceof List<?> list) {
            return list.contains(right);
        }
        return false;
    }

    /**
     * 检查左值是否匹配右值指定的正则表达式
     *
     * <p>
     * 将 left 和 right 都转换为字符串，然后使用 String.matches() 进行正则匹配。
     * 如果 left 或 right 为 null，返回 false。
     * </p>
     *
     * 【读取】
     * <table border="1">
     *   <tr><th>参数</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>left</td><td>Object</td><td>要匹配的字符串</td></tr>
     *   <tr><td>right</td><td>Object</td><td>正则表达式</td></tr>
     * </table>
     *
     * 【输出】
     * <table border="1">
     *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>result</td><td>boolean</td><td>匹配成功返回 true</td></tr>
     * </table>
     *
     * @param left  要匹配的字符串
     * @param right 正则表达式
     * @return 是否匹配正则
     */
    private boolean regex(Object left, Object right) {
        if (left == null || right == null) {
            return false;
        }
        return String.valueOf(left).matches(String.valueOf(right));
    }

    /**
     * 比较两个数值的大小
     *
     * <p>
     * 将 left 和 right 转换为 Double 类型后进行数值比较。
     * 如果任一值为 null 或无法转换为数值，返回 0（视为相等）。
     * </p>
     *
     * 【读取】
     * <table border="1">
     *   <tr><th>参数</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>left</td><td>Object</td><td>左数值</td></tr>
     *   <tr><td>right</td><td>Object</td><td>右数值</td></tr>
     * </table>
     *
     * 【输出】
     * <table border="1">
     *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>result</td><td>int</td><td>&lt;0 左小于右，=0 相等，&gt;0 左大于右</td></tr>
     * </table>
     *
     * @param left  左数值
     * @param right 右数值
     * @return 数值比较结果
     */
    private int compareNumber(Object left, Object right) {
        if (left == null || right == null) {
            return 0;
        }
        Double l = toDouble(left);
        Double r = toDouble(right);
        if (l == null || r == null) {
            return 0;
        }
        return Double.compare(l, r);
    }

    /**
     * 将对象转换为 Double 类型
     *
     * <p>
     * 尝试将任意对象转换为 Double：
     * - 如果对象已经是 Number 类型，直接调用 doubleValue()
     * - 否则尝试调用 Double.parseDouble(String.valueOf(value))
     * - 转换失败返回 null
     * </p>
     *
     * 【读取】
     * <table border="1">
     *   <tr><th>参数</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>value</td><td>Object</td><td>要转换的值</td></tr>
     * </table>
     *
     * 【输出】
     * <table border="1">
     *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>result</td><td>Double</td><td>转换后的 Double，失败时返回 null</td></tr>
     * </table>
     *
     * @param value 要转换的值
     * @return 转换后的 Double
     */
    private Double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 规范化值
     *
     * <p>
     * 对比较操作的值进行规范化处理：
     * - String 类型会调用 trim() 去除首尾空白
     * - 其他类型直接返回原值
     * 确保字符串比较时不会因空白字符导致误判。
     * </p>
     *
     * 【读取】
     * <table border="1">
     *   <tr><th>参数</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>value</td><td>Object</td><td>要规范化的值</td></tr>
     * </table>
     *
     * 【输出】
     * <table border="1">
     *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>result</td><td>Object</td><td>规范化后的值</td></tr>
     * </table>
     *
     * @param value 要规范化的值
     * @return 规范化后的值
     */
    private Object normalize(Object value) {
        if (value instanceof String s) {
            return s.trim();
        }
        return value;
    }

    /**
     * 评估 SpEL 表达式
     *
     * <p>
     * 将字符串作为 Spring Expression Language (SpEL) 表达式求值。
     * 表达式在 IngestionContext 的上下文中执行，可以访问 context 的所有属性。
     * 同时将 context 绑定到变量 ctx，方便表达式编写。
     * </p>
     *
     * <h3>使用示例</h3>
     * <pre>
     * // 评估 mimeType 是否为 "application/pdf"
     * evalSpel(context, "mimeType == 'application/pdf'")
     *
     * // 评估 source.type 是否为 "URL"
     * evalSpel(context, "source.type.value == 'URL'")
     *
     * // 使用 ctx 变量
     * evalSpel(context, "ctx.rawText.length() > 100")
     * </pre>
     *
     * 【读取】
     * <table border="1">
     *   <tr><th>参数</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>context</td><td>IngestionContext</td><td>SpEL 表达式执行的上下文</td></tr>
     *   <tr><td>expression</td><td>String</td><td>SpEL 表达式字符串</td></tr>
     * </table>
     *
     * 【输出】
     * <table border="1">
     *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>result</td><td>boolean</td><td>表达式求值结果</td></tr>
     * </table>
     *
     * @param context   摄入上下文
     * @param expression SpEL 表达式
     * @return 表达式求值结果
     */
    private boolean evalSpel(IngestionContext context, String expression) {
        try {
            StandardEvaluationContext ctx = new StandardEvaluationContext(context);
            ctx.setVariable("ctx", context);
            Boolean result = parser.parseExpression(expression).getValue(ctx, Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return false;
        }
    }
}
