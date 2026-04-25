
package com.rks.core.parser;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文档解析器选择器（策略模式）
 * <p>
 * 负责管理和选择合适的文档解析策略。根据解析器类型或 MIME 类型，
 * 动态选择最合适的解析器实现，体现了策略模式的核心思想
 * </p>
 * <p>
 * 支持两种选择方式：
 * <ul>
 *   <li>按类型选择：通过 {@link #select(String)} 指定解析器类型（如 {@link ParserType#TIKA}, {@link ParserType#MARKDOWN}）</li>
 *   <li>按 MIME 类型选择：通过 {@link #selectByMimeType(String)} 自动匹配支持该 MIME 类型的解析器</li>
 * </ul>
 * </p>
 */
@Component
public class DocumentParserSelector {

    private final List<DocumentParser> strategies;
    private final Map<String, DocumentParser> strategyMap;

    public DocumentParserSelector(List<DocumentParser> parsers) {
        this.strategies = parsers;
        this.strategyMap = parsers.stream()
                .collect(Collectors.toMap(
                        DocumentParser::getParserType,
                        Function.identity(),
                        (existing, replacement) -> existing
                ));
    }

    /**
     * 根据解析器类型选择解析策略
     *
     * @param parserType 解析器类型（如 {@link ParserType#TIKA}, {@link ParserType#MARKDOWN}）
     * @return 解析器实例，如果不存在则返回 null
     */
    public DocumentParser select(String parserType) {
        return strategyMap.get(parserType);
    }

    /**
     * 根据 MIME 类型自动选择合适的解析策略
     *
     * <p>
     * 该方法遍历所有注册的解析器，返回第一个支持指定 MIME 类型的解析器。
     * 如果没有找到匹配的解析器，则返回默认的 Tika 解析器作为兜底。
     * </p>
     *
     * <h2>选择逻辑</h2>
     * <ol>
     *   <li><b>流式过滤</b>：遍历所有解析器，使用 filter() 筛选支持该 MIME 类型的解析器</li>
     *   <li><b>优先级选择</b>：findFirst() 返回第一个匹配的解析器</li>
     *   <li><b>默认兜底</b>：如果没有匹配，使用 orElseGet() 返回 Tika 解析器</li>
     * </ol>
     *
     * <h2>解析器优先级</h2>
     * <ul>
     *   <li>Markdown 解析器 → text/markdown</li>
     *   <li>Tika 解析器 → 其他大部分格式（PDF、Word、Excel、PPT 等）</li>
     * </ul>
     *
     * @param mimeType MIME 类型（如 "application/pdf", "text/markdown"）
     * @return 支持该 MIME 类型的解析器，如果没有则返回默认的 Tika 解析器
     */
    public DocumentParser selectByMimeType(String mimeType) {
        // ========== 步骤1：流式过滤 ==========
        // 遍历所有解析器，筛选出支持该 MIME 类型的解析器
        // 每个解析器的 supports() 方法决定是否支持该类型
        return strategies.stream()
                .filter(parser -> parser.supports(mimeType))  // 过滤支持该 MIME 类型的解析器
                .findFirst()                                 // 取第一个匹配的解析器
                // ========== 步骤2：默认兜底 ==========
                // 如果没有找到匹配的解析器，返回 Tika 解析器作为默认
                // Tika 支持大部分常见文档格式，是一个通用的解析器
                .orElseGet(() -> select(ParserType.TIKA.getType()));
    }

    /**
     * 获取所有可用的解析策略
     *
     * @return 解析器列表
     */
    public List<DocumentParser> getAllStrategies() {
        return List.copyOf(strategies);
    }

    /**
     * 获取所有解析器类型
     *
     * @return 解析器类型列表
     */
    public List<String> getAvailableTypes() {
        return strategies.stream()
                .map(DocumentParser::getParserType)
                .toList();
    }
}
