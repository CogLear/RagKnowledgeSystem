
package com.rks.ingestion.domain.settings;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 解析器设置实体类
 * 定义文档解析节点的配置参数，包含多个解析规则
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParserSettings {

    /**
     * 解析规则
     * 支持三种格式：
     * - 字符串 "ALL"：表示所有类型
     * - 对象 {mimeType: "ALL"}：单条规则
     * - 数组 [{mimeType: "PDF"}, {mimeType: "WORD"}]：多条规则
     */
    private Object rules;

    /**
     * 规范化后的规则列表（缓存）
     * 通过 {@link #getNormalizedRules()} 获取，避免重复转换
     */
    @JsonIgnore
    private List<ParserRule> normalizedRules;

    /**
     * 获取规范化后的规则列表
     * 支持：
     * - null 或空：返回空列表
     * - 字符串 "ALL"：返回 [{mimeType:"ALL"}]
     * - 字符串 "PDF,WORD"：返回 [{mimeType:"PDF"}, {mimeType:"WORD"}]
     * - 单个对象 {mimeType:"XX"}：返回 [rule]
     * - 数组 [{}]：直接返回
     *
     * @return 规范化后的规则列表
     */
    public List<ParserRule> getNormalizedRules() {
        if (normalizedRules != null) {
            return normalizedRules;
        }
        normalizedRules = normalizeRules(this.rules);
        return normalizedRules;
    }

    /**
     * 规范化 rules 字段为 List<ParserRule>
     */
    private List<ParserRule> normalizeRules(Object rules) {
        if (rules == null) {
            return List.of();
        }
        if (rules instanceof String s) {
            String trimmed = s.trim();
            if ("ALL".equalsIgnoreCase(trimmed) || trimmed.isEmpty()) {
                return List.of(ParserRule.builder().mimeType("ALL").build());
            }
            // 可能是逗号分隔的多类型 "PDF,WORD"
            return Arrays.stream(trimmed.split(","))
                    .map(String::trim)
                    .filter(x -> !x.isEmpty())
                    .map(x -> ParserRule.builder().mimeType(x.toUpperCase(Locale.ROOT)).build())
                    .collect(Collectors.toList());
        }
        if (rules instanceof List<?> list) {
            List<ParserRule> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof ParserRule rule) {
                    result.add(rule);
                } else if (item instanceof Map<?, ?> map) {
                    result.add(mapToRule(map));
                }
            }
            return result;
        }
        if (rules instanceof ParserRule rule) {
            return List.of(rule);
        }
        if (rules instanceof Map<?, ?> map) {
            return List.of(mapToRule(map));
        }
        return List.of();
    }

    /**
     * 将 Map 转换为 ParserRule
     */
    @SuppressWarnings("unchecked")
    private ParserRule mapToRule(Map<?, ?> map) {
        String mimeType = String.valueOf(map.get("mimeType"));
        if (mimeType == null || "ALL".equalsIgnoreCase(mimeType) || mimeType.isEmpty()) {
            return ParserRule.builder().mimeType("ALL").build();
        }
        Map<String, Object> options = (Map<String, Object>) map.get("options");
        return ParserRule.builder()
                .mimeType(mimeType.toUpperCase(Locale.ROOT))
                .options(options)
                .build();
    }

    /**
     * 判断是否有配置规则
     */
    public boolean hasRules() {
        return getNormalizedRules() != null && !getNormalizedRules().isEmpty();
    }

    /**
     * 解析规则配置
     * 定义单个解析规则，指定哪些MIME类型应该使用哪种解析器
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ParserRule {

        /**
         * 文档类型
         * 如 PDF、WORD、MARKDOWN 等
         */
        private String mimeType;

        /**
         * 解析器的额外配置选项
         */
        private Map<String, Object> options;
    }
}
