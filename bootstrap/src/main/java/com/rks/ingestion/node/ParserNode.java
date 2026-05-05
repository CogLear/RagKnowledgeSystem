
package com.rks.ingestion.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rks.core.parser.DocumentParser;
import com.rks.core.parser.DocumentParserSelector;
import com.rks.core.parser.ParseResult;
import com.rks.core.parser.ParserType;
import com.rks.framework.exception.ClientException;
import com.rks.ingestion.domain.context.IngestionContext;
import com.rks.ingestion.domain.context.StructuredDocument;
import com.rks.ingestion.domain.enums.IngestionNodeType;
import com.rks.ingestion.domain.pipeline.NodeConfig;
import com.rks.ingestion.domain.result.NodeResult;
import com.rks.ingestion.domain.settings.ParserSettings;
import com.rks.ingestion.util.MimeTypeDetector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文档解析节点
 * 负责将输入的字节流（如 PDF、Word、Excel 等）解析为结构化的文本或文档对象
 */
@Component
public class ParserNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final DocumentParserSelector parserSelector;

    public ParserNode(ObjectMapper objectMapper, DocumentParserSelector parserSelector) {
        this.objectMapper = objectMapper;
        this.parserSelector = parserSelector;
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.PARSER.getValue();
    }

    /**
     * 执行文档解析逻辑
     *
     * <p>
     * ParserNode 负责将文档的原始字节流解析为结构化的纯文本。
     * 支持 PDF、Word、Excel、PPT、Markdown、纯文本等多种格式。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>输入校验</b>：确保 context 中存在 rawBytes</li>
     *   <li><b>MIME 检测</b>：如果未指定 MIME 类型，根据字节流和文件名自动检测</li>
     *   <li><b>规则匹配</b>：根据配置的文件类型规则进行校验和匹配</li>
     *   <li><b>解析执行</b>：调用 Tika 解析器将字节流转换为文本</li>
     *   <li><b>结果封装</b>：将文本和解析元数据存入 context</li>
     * </ol>
     *
     * <h2>文件类型校验</h2>
     * <p>
     * 如果节点配置了 {@link ParserSettings#getRules()}，会校验当前文件类型是否在允许范围内。
     * 支持通配符 "ALL" 允许所有类型，或指定具体类型如 PDF、MARKDOWN、WORD 等。
     * </p>
     *
     * <h2>MIME 类型推断</h2>
     * <ul>
     *   <li>优先使用 context 中已有的 mimeType</li>
     *   <li>其次根据文件名后缀推断（如 .pdf → PDF）</li>
     *   <li>最后根据字节流内容检测（Magic Number）</li>
     * </ul>
     *
     * <h2>流水线数据传递</h2>
     * <table border="1" cellpadding="5">
     *   <tr><th>方向</th><th>字段</th><th>说明</th></tr>
     *   <tr><td>【读取】</td><td>context.rawBytes</td><td>文档原始字节数组</td></tr>
     *   <tr><td>【读取】</td><td>context.mimeType</td><td>MIME 类型（未设置则自动检测）</td></tr>
     *   <tr><td>【读取】</td><td>context.source.fileName</td><td>文件名（用于类型推断）</td></tr>
     *   <tr><td>【写入】</td><td>context.rawText</td><td>解析后的纯文本内容</td></tr>
     *   <tr><td>【写入】</td><td>context.mimeType</td><td>检测到的 MIME 类型</td></tr>
     *   <tr><td>【写入】</td><td>context.document</td><td>结构化文档对象（含文本和元数据）</td></tr>
     * </table>
     *
     * <h2>流水线位置</h2>
     * <pre>
     * Fetcher → 【Parser】 → [rawText, document] → Enhancer/Chunker
     * </pre>
     *
     * @param context 摄取上下文，包含原始字节和解析结果存储
     * @param config  节点配置，包含解析规则和选项
     * @return 解析结果，成功时包含文本长度
     * @see com.rks.core.parser.DocumentParser
     * @see ParseResult
     */
    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        // ========== 步骤1：输入校验 ==========
        // 确保 FetcherNode 已经获取了文档原始字节
        if (context.getRawBytes() == null || context.getRawBytes().length == 0) {
            return NodeResult.fail(new ClientException("解析器缺少原始字节"));
        }

        // ========== 步骤2：MIME 检测 ==========
        // 优先使用 Fetcher 检测到的 mimeType；若缺失，则通过字节流（Magic Number）和文件名推断
        String mimeType = context.getMimeType();
        if (!StringUtils.hasText(mimeType)) {
            String fileName = context.getSource() == null ? null : context.getSource().getFileName();
            mimeType = MimeTypeDetector.detect(context.getRawBytes(), fileName);
            context.setMimeType(mimeType); // 回填检测到的 MIME 类型
        }

        // ========== 步骤3：配置解析 ==========
        // 解析节点配置中的文件类型规则（允许哪些类型、解析选项等）
        ParserSettings settings = parseSettings(config.getSettings());
        String fileName = context.getSource() == null ? null : context.getSource().getFileName();

        // ========== 步骤4：规则校验 ==========
        // 根据配置的文件类型规则进行校验，确保当前文件类型在允许范围内
        // 如果配置了规则但文件类型不匹配，直接抛出异常拒绝处理
        validateMimeType(settings, mimeType, fileName);

        // ========== 步骤5：规则匹配 ==========
        // 根据 MIME 类型和文件名匹配具体的解析规则（用于提取特定选项）
        ParserSettings.ParserRule rule = matchRule(settings, mimeType, fileName);

        // ========== 步骤6：解析执行 ==========
        // 获取 Tika 文档解析器（Apache Tika 支持 PDF、Word、Excel、PPT、Markdown 等多种格式）
        DocumentParser parser = parserSelector.select(ParserType.TIKA.getType());
        if (parser == null) {
            return NodeResult.fail(new ClientException("未配置 Tika 解析器"));
        }

        // 根据匹配的规则获取解析选项（无匹配规则则使用空选项）
        Map<String, Object> options = rule == null ? Collections.emptyMap() : rule.getOptions();
        // 调用 Tika 解析器将字节流转换为文本，返回 ParseResult（包含纯文本和元数据）
        ParseResult result = parser.parse(context.getRawBytes(), mimeType, options);

        // ========== 步骤7：结果封装 ==========
        // ① 将解析后的纯文本写入 context，传递给下一个节点（Enhancer / Chunker）
        context.setRawText(result.text());

        // ② 将 ParseResult 封装为 StructuredDocument（包含文本和解析元数据），写入 context
        StructuredDocument document = StructuredDocument.builder()
                .text(result.text())
                .metadata(result.metadata())
                .build();
        context.setDocument(document);

        // 返回成功结果，包含解析文本的长度
        return NodeResult.ok("解析文本长度=" + (result.text() == null ? 0 : result.text().length()));
    }

    /**
     * 验证文件类型是否符合配置的规则
     * 如果配置了规则但文件类型不匹配，则抛出异常
     */
    private void validateMimeType(ParserSettings settings, String mimeType, String fileName) {
        if (settings == null || settings.getRules() == null || ((List) settings.getRules()).isEmpty()) {
            // 没有配置规则，允许所有类型
            return;
        }

        String resolvedType = resolveType(mimeType, fileName);

        // 检查是否有匹配的规则
        boolean hasMatch = false;
        for (ParserSettings.ParserRule rule : (List<ParserSettings.ParserRule>) settings.getRules()) {
            if (rule == null || !StringUtils.hasText(rule.getMimeType())) {
                continue;
            }
            String configured = normalizeType(rule.getMimeType());
            if (!StringUtils.hasText(configured)) {
                continue;
            }
            if ("ALL".equals(configured) || configured.equalsIgnoreCase(resolvedType)) {
                hasMatch = true;
                break;
            }
        }

        if (!hasMatch) {
            // 构建允许的类型列表用于错误提示
            List<String> allowedTypes = ((List<ParserSettings.ParserRule>) settings.getRules()).stream()
                    .filter(rule -> rule != null && StringUtils.hasText(rule.getMimeType()))
                    .map(rule -> normalizeType(rule.getMimeType()))
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();

            throw new ClientException(
                    String.format("文件类型不符合要求。当前文件类型: %s，允许的类型: %s",
                            resolvedType,
                            String.join(", ", allowedTypes))
            );
        }
    }

    private ParserSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return ParserSettings.builder().rules(List.of()).build();
        }
        ParserSettings settings = objectMapper.convertValue(node, ParserSettings.class);
        // 规范化 rules 字段：支持字符串、对象、数组三种格式
        settings.setRules(normalizeRules(settings.getRules()));
        return settings;
    }

    /**
     * 规范化 rules 字段为 List<ParserRule>
     * 支持：
     * - null 或空：返回空列表
     * - 字符串 "ALL"：返回 [{mimeType:"ALL"}]
     * - 单个对象 {mimeType:"XX"}：返回 [rule]
     * - 数组 [{}]：直接返回
     */
    private List<ParserSettings.ParserRule> normalizeRules(Object rules) {
        if (rules == null) {
            return List.of();
        }
        if (rules instanceof String) {
            String s = ((String) rules).trim();
            if ("ALL".equalsIgnoreCase(s) || s.isEmpty()) {
                return List.of(ParserSettings.ParserRule.builder().mimeType("ALL").build());
            }
            // 可能是逗号分隔的多类型 "PDF,WORD"
            return Arrays.stream(s.split(","))
                    .map(String::trim)
                    .filter(x -> !x.isEmpty())
                    .map(x -> ParserSettings.ParserRule.builder().mimeType(x.toUpperCase(java.util.Locale.ROOT)).build())
                    .collect(Collectors.toList());
        }
        if (rules instanceof List) {
            return (List) rules;
        }
        if (rules instanceof ParserSettings.ParserRule) {
            return List.of((ParserSettings.ParserRule) rules);
        }
        if (rules instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) rules;
            if ("ALL".equalsIgnoreCase(String.valueOf(map.getOrDefault("mimeType", "")))) {
                return List.of(ParserSettings.ParserRule.builder().mimeType("ALL").build());
            }
            ParserSettings.ParserRule rule = ParserSettings.ParserRule.builder()
                    .mimeType(String.valueOf(map.getOrDefault("mimeType", "ALL")))
                    .options((Map<String, Object>) map.get("options"))
                    .build();
            return List.of(rule);
        }
        return List.of();
    }

    private ParserSettings.ParserRule matchRule(ParserSettings settings, String mimeType, String fileName) {
        if (settings == null || settings.getRules() == null || ((List) settings.getRules()).isEmpty()) {
            return null;
        }
        String resolvedType = resolveType(mimeType, fileName);
        for (ParserSettings.ParserRule rule : (List<ParserSettings.ParserRule>) settings.getRules()) {
            if (rule == null || !StringUtils.hasText(rule.getMimeType())) {
                continue;
            }
            String configured = normalizeType(rule.getMimeType());
            if (!StringUtils.hasText(configured)) {
                continue;
            }
            if ("ALL".equals(configured) || configured.equalsIgnoreCase(resolvedType)) {
                return rule;
            }
        }
        return null;
    }

    private String resolveType(String mimeType, String fileName) {
        String byName = resolveTypeByName(fileName);
        if (StringUtils.hasText(byName)) {
            return byName;
        }
        if (!StringUtils.hasText(mimeType)) {
            return "UNKNOWN";
        }
        String lower = mimeType.trim().toLowerCase();
        if (lower.contains("pdf")) {
            return "PDF";
        }
        if (lower.contains("markdown")) {
            return "MARKDOWN";
        }
        if (lower.contains("word") || lower.contains("msword") || lower.contains("wordprocessingml")) {
            return "WORD";
        }
        if (lower.contains("excel") || lower.contains("spreadsheetml")) {
            return "EXCEL";
        }
        if (lower.contains("powerpoint") || lower.contains("presentation")) {
            return "PPT";
        }
        if (lower.startsWith("image/")) {
            return "IMAGE";
        }
        if (lower.startsWith("text/")) {
            return "TEXT";
        }
        return "UNKNOWN";
    }

    private String resolveTypeByName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return "PDF";
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "MARKDOWN";
        }
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) {
            return "WORD";
        }
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) {
            return "EXCEL";
        }
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) {
            return "PPT";
        }
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp")) {
            return "IMAGE";
        }
        if (lower.endsWith(".txt")) {
            return "TEXT";
        }
        return null;
    }

    private String normalizeType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim().toUpperCase();
        return switch (value) {
            case "*", "ALL", "DEFAULT" -> "ALL";
            case "MD", "MARKDOWN" -> "MARKDOWN";
            case "DOC", "DOCX", "WORD" -> "WORD";
            case "XLS", "XLSX", "EXCEL" -> "EXCEL";
            case "PPT", "PPTX", "POWERPOINT" -> "PPT";
            case "TXT", "TEXT" -> "TEXT";
            case "PNG", "JPG", "JPEG", "GIF", "BMP", "WEBP", "IMAGE", "IMG" -> "IMAGE";
            case "PDF" -> "PDF";
            default -> value;
        };
    }
}
