
package com.rks.ingestion.engine;


import com.rks.ingestion.domain.context.DocumentSource;
import com.rks.ingestion.domain.context.IngestionContext;
import com.rks.ingestion.domain.enums.IngestionNodeType;
import com.rks.ingestion.domain.pipeline.NodeConfig;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 节点输出提取器
 *
 * <p>
 * NodeOutputExtractor 负责从 IngestionContext 中提取特定节点的输出信息，
 * 为管道的日志记录、调试和问题排查提供标准化的输出视图。
 * </p>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>节点类型识别</b> - 根据 NodeConfig 的 nodeType 识别当前节点类型</li>
 *   <li><b>差异化提取</b> - 不同节点类型提取不同的输出字段</li>
 *   <li><b>通用 fallback</b> - 未知节点类型时提取所有可用字段</li>
 * </ul>
 *
 * <h2>节点输出字段映射</h2>
 * <table border="1">
 *   <tr><th>节点类型</th><th>提取字段</th></tr>
 *   <tr><td>FETCHER</td><td>source, mimeType, rawBytesLength, rawBytesBase64</td></tr>
 *   <tr><td>PARSER</td><td>mimeType, rawText, document</td></tr>
 *   <tr><td>ENHANCER</td><td>enhancedText, keywords, questions, metadata</td></tr>
 *   <tr><td>CHUNKER</td><td>chunkCount, chunks</td></tr>
 *   <tr><td>ENRICHER</td><td>chunkCount, chunks</td></tr>
 *   <tr><td>INDEXER</td><td>settings, chunkCount, chunks</td></tr>
 * </table>
 *
 * @see IngestionContext
 * @see NodeConfig
 * @see IngestionNodeType
 */
@Component
public class NodeOutputExtractor {

    /**
     * 从上下文提取指定节点的输出信息
     *
     * <p>
     * 根据节点配置中的 nodeType 字段，确定节点类型并调用对应的提取方法。
     * 如果节点类型无法识别，则提取所有可用字段作为通用输出。
     * </p>
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li><b>空值检查</b> - context 或 config 为空时返回空 Map</li>
     *   <li><b>节点类型解析</b> - 从 config.getNodeType() 解析 IngestionNodeType</li>
     *   <li><b>类型匹配</b> - 根据节点类型分发到对应的输出提取方法</li>
     *   <li><b>通用 fallback</b> - 类型无法识别时提取所有可用字段</li>
     * </ol>
     *
     * 【读取】
     * <table border="1">
     *   <tr><th>参数</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>context</td><td>IngestionContext</td><td>摄入上下文，包含文档处理的所有中间状态</td></tr>
     *   <tr><td>config</td><td>NodeConfig</td><td>节点配置，包含 nodeType 等信息</td></tr>
     * </table>
     *
     * 【输出】
     * <table border="1">
     *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>extracted</td><td>Map&lt;String, Object&gt;</td><td>节点输出信息 Map</td></tr>
     * </table>
     *
     * @param context 摄入上下文
     * @param config  节点配置
     * @return 节点输出信息的 Map 形式
     */
    public Map<String, Object> extract(IngestionContext context, NodeConfig config) {
        if (context == null || config == null) {
            return Map.of();
        }
        IngestionNodeType nodeType = resolveNodeType(config.getNodeType());
        if (nodeType == null) {
            return genericOutput(context);
        }
        return switch (nodeType) {
            case FETCHER -> fetcherOutput(context);
            case PARSER -> parserOutput(context);
            case ENHANCER -> enhancerOutput(context);
            case CHUNKER -> chunkerOutput(context);
            case ENRICHER -> enricherOutput(context);
            case INDEXER -> indexerOutput(context, config);
        };
    }

    /**
     * 提取 FETCHER 节点的输出信息
     *
     * <p>
     * FETCHER 节点负责从数据源获取原始文档，其输出包含：
     * 文档来源信息（类型、位置、文件名）、MIME 类型以及原始字节数据。
     * 原始字节数据以 Base64 编码形式返回，便于日志记录和问题排查。
     * </p>
     *
     * 【读取】
     * <table border="1">
     *   <tr><th>字段</th><th>来源</th><th>说明</th></tr>
     *   <tr><td>source</td><td>context.getSource()</td><td>文档来源对象</td></tr>
     *   <tr><td>mimeType</td><td>context.getMimeType()</td><td>文档 MIME 类型</td></tr>
     *   <tr><td>rawBytes</td><td>context.getRawBytes()</td><td>原始字节数据</td></tr>
     * </table>
     *
     * 【输出】
     * <table border="1">
     *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>source</td><td>Map</td><td>来源信息：type, location, fileName</td></tr>
     *   <tr><td>mimeType</td><td>String</td><td>MIME 类型</td></tr>
     *   <tr><td>rawBytesLength</td><td>Integer</td><td>原始数据字节长度</td></tr>
     *   <tr><td>rawBytesBase64</td><td>String</td><td>Base64 编码的原始数据</td></tr>
     * </table>
     *
     * @param context 摄入上下文
     * @return FETCHER 节点输出信息
     */
    private Map<String, Object> fetcherOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        DocumentSource source = context.getSource();
        if (source != null) {
            Map<String, Object> sourceView = new LinkedHashMap<>();
            sourceView.put("type", source.getType() == null ? null : source.getType().getValue());
            sourceView.put("location", source.getLocation());
            sourceView.put("fileName", source.getFileName());
            output.put("source", sourceView);
        }
        output.put("mimeType", context.getMimeType());
        byte[] raw = context.getRawBytes();
        if (raw != null) {
            output.put("rawBytesLength", raw.length);
            output.put("rawBytesBase64", Base64.getEncoder().encodeToString(raw));
        }
        return output;
    }

    /**
     * 提取 PARSER 节点的输出信息
     *
     * <p>
     * PARSER 节点负责将原始文档解析为结构化内容，其输出包含：
     * MIME 类型、原始文本内容以及结构化文档对象。
     * </p>
     *
     * 【读取】
     * <table border="1">
     *   <tr><th>字段</th><th>来源</th><th>说明</th></tr>
     *   <tr><td>mimeType</td><td>context.getMimeType()</td><td>文档 MIME 类型</td></tr>
     *   <tr><td>rawText</td><td>context.getRawText()</td><td>解析后的原始文本</td></tr>
     *   <tr><td>document</td><td>context.getDocument()</td><td>结构化文档对象</td></tr>
     * </table>
     *
     * 【输出】
     * <table border="1">
     *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>mimeType</td><td>String</td><td>MIME 类型</td></tr>
     *   <tr><td>rawText</td><td>String</td><td>解析后的原始文本</td></tr>
     *   <tr><td>document</td><td>Object</td><td>结构化文档对象</td></tr>
     * </table>
     *
     * @param context 摄入上下文
     * @return PARSER 节点输出信息
     */
    private Map<String, Object> parserOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("mimeType", context.getMimeType());
        output.put("rawText", context.getRawText());
        output.put("document", context.getDocument());
        return output;
    }

    /**
     * 提取 ENHANCER 节点的输出信息
     *
     * <p>
     * ENHANCER 节点负责对文本进行增强处理，其输出包含：
     * 增强后的文本、提取的关键词、自动生成的问题以及元数据。
     * </p>
     *
     * 【读取】
     * <table border="1">
     *   <tr><th>字段</th><th>来源</th><th>说明</th></tr>
     *   <tr><td>enhancedText</td><td>context.getEnhancedText()</td><td>增强后的文本内容</td></tr>
     *   <tr><td>keywords</td><td>context.getKeywords()</td><td>提取的关键词列表</td></tr>
     *   <tr><td>questions</td><td>context.getQuestions()</td><td>自动生成的问题列表</td></tr>
     *   <tr><td>metadata</td><td>context.getMetadata()</td><td>文档元数据</td></tr>
     * </table>
     *
     * 【输出】
     * <table border="1">
     *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>enhancedText</td><td>String</td><td>增强后的文本内容</td></tr>
     *   <tr><td>keywords</td><td>List</td><td>提取的关键词列表</td></tr>
     *   <tr><td>questions</td><td>List</td><td>自动生成的问题列表</td></tr>
     *   <tr><td>metadata</td><td>Map</td><td>文档元数据</td></tr>
     * </table>
     *
     * @param context 摄入上下文
     * @return ENHANCER 节点输出信息
     */
    private Map<String, Object> enhancerOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("enhancedText", context.getEnhancedText());
        output.put("keywords", context.getKeywords());
        output.put("questions", context.getQuestions());
        output.put("metadata", context.getMetadata());
        return output;
    }

    /**
     * 提取 CHUNKER 节点的输出信息
     *
     * <p>
     * CHUNKER 节点负责将文档切分为块（Chunk），其输出包含：
     * 切块数量和切块列表。切块是后续索引和检索的基本单元。
     * </p>
     *
     * 【读取】
     * <table border="1">
     *   <tr><th>字段</th><th>来源</th><th>说明</th></tr>
     *   <tr><td>chunks</td><td>context.getChunks()</td><td>文档切块列表</td></tr>
     * </table>
     *
     * 【输出】
     * <table border="1">
     *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>chunkCount</td><td>Integer</td><td>切块数量，为 0 表示无切块</td></tr>
     *   <tr><td>chunks</td><td>List</td><td>切块列表</td></tr>
     * </table>
     *
     * @param context 摄入上下文
     * @return CHUNKER 节点输出信息
     */
    private Map<String, Object> chunkerOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("chunkCount", context.getChunks() == null ? 0 : context.getChunks().size());
        output.put("chunks", context.getChunks());
        return output;
    }

    /**
     * 提取 ENRICHER 节点的输出信息
     *
     * <p>
     * ENRICHER 节点负责对切块进行富化处理（如添加向量嵌入等），其输出包含：
     * 切块数量和富化后的切块列表。输出内容与 CHUNKER 类似。
     * </p>
     *
     * 【读取】
     * <table border="1">
     *   <tr><th>字段</th><th>来源</th><th>说明</th></tr>
     *   <tr><td>chunks</td><td>context.getChunks()</td><td>富化后的切块列表</td></tr>
     * </table>
     *
     * 【输出】
     * <table border="1">
     *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>chunkCount</td><td>Integer</td><td>切块数量，为 0 表示无切块</td></tr>
     *   <tr><td>chunks</td><td>List</td><td>富化后的切块列表</td></tr>
     * </table>
     *
     * @param context 摄入上下文
     * @return ENRICHER 节点输出信息
     */
    private Map<String, Object> enricherOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("chunkCount", context.getChunks() == null ? 0 : context.getChunks().size());
        output.put("chunks", context.getChunks());
        return output;
    }

    /**
     * 提取 INDEXER 节点的输出信息
     *
     * <p>
     * INDEXER 节点负责将切块索引到向量数据库，其输出包含：
     * 索引配置信息、切块数量和切块列表。配置信息来自 NodeConfig 的 settings 字段。
     * </p>
     *
     * 【读取】
     * <table border="1">
     *   <tr><th>字段</th><th>来源</th><th>说明</th></tr>
     *   <tr><td>settings</td><td>config.getSettings()</td><td>索引配置信息</td></tr>
     *   <tr><td>chunks</td><td>context.getChunks()</td><td>待索引的切块列表</td></tr>
     * </table>
     *
     * 【输出】
     * <table border="1">
     *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>settings</td><td>Object</td><td>索引配置信息</td></tr>
     *   <tr><td>chunkCount</td><td>Integer</td><td>切块数量</td></tr>
     *   <tr><td>chunks</td><td>List</td><td>待索引的切块列表</td></tr>
     * </table>
     *
     * @param context 摄入上下文
     * @param config  节点配置
     * @return INDEXER 节点输出信息
     */
    private Map<String, Object> indexerOutput(IngestionContext context, NodeConfig config) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("settings", config.getSettings());
        output.put("chunkCount", context.getChunks() == null ? 0 : context.getChunks().size());
        output.put("chunks", context.getChunks());
        return output;
    }

    /**
     * 通用节点输出提取
     *
     * <p>
     * 当节点类型无法识别时，提取 IngestionContext 中所有可用的字段信息。
     * 这是一种安全的 fallback 策略，确保即使配置错误也能获取有用的调试信息。
     * </p>
     *
     * 【读取】
     * <table border="1">
     *   <tr><th>字段</th><th>来源</th><th>说明</th></tr>
     *   <tr><td>mimeType</td><td>context.getMimeType()</td><td>MIME 类型</td></tr>
     *   <tr><td>rawText</td><td>context.getRawText()</td><td>原始文本</td></tr>
     *   <tr><td>enhancedText</td><td>context.getEnhancedText()</td><td>增强文本</td></tr>
     *   <tr><td>keywords</td><td>context.getKeywords()</td><td>关键词</td></tr>
     *   <tr><td>questions</td><td>context.getQuestions()</td><td>问题</td></tr>
     *   <tr><td>metadata</td><td>context.getMetadata()</td><td>元数据</td></tr>
     *   <tr><td>chunks</td><td>context.getChunks()</td><td>切块列表</td></tr>
     * </table>
     *
     * 【输出】
     * <table border="1">
     *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>mimeType</td><td>String</td><td>MIME 类型</td></tr>
     *   <tr><td>rawText</td><td>String</td><td>原始文本</td></tr>
     *   <tr><td>enhancedText</td><td>String</td><td>增强文本</td></tr>
     *   <tr><td>keywords</td><td>List</td><td>关键词列表</td></tr>
     *   <tr><td>questions</td><td>List</td><td>问题列表</td></tr>
     *   <tr><td>metadata</td><td>Map</td><td>元数据</td></tr>
     *   <tr><td>chunks</td><td>List</td><td>切块列表</td></tr>
     * </table>
     *
     * @param context 摄入上下文
     * @return 所有可用字段的 Map
     */
    private Map<String, Object> genericOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("mimeType", context.getMimeType());
        output.put("rawText", context.getRawText());
        output.put("enhancedText", context.getEnhancedText());
        output.put("keywords", context.getKeywords());
        output.put("questions", context.getQuestions());
        output.put("metadata", context.getMetadata());
        output.put("chunks", context.getChunks());
        return output;
    }

    /**
     * 解析节点类型字符串为枚举
     *
     * <p>
     * 将配置中的节点类型字符串（如 "FETCHER"、"PARSER" 等）转换为
     * IngestionNodeType 枚举值。如果字符串无效或为空，返回 null。
     * </p>
     *
     * 【读取】
     * <table border="1">
     *   <tr><th>参数</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>nodeType</td><td>String</td><td>节点类型字符串</td></tr>
     * </table>
     *
     * 【输出】
     * <table border="1">
     *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
     *   <tr><td>result</td><td>IngestionNodeType</td><td>解析后的枚举值，null 表示无法解析</td></tr>
     * </table>
     *
     * @param nodeType 节点类型字符串
     * @return 对应的 IngestionNodeType 枚举值，无法解析时返回 null
     */
    private IngestionNodeType resolveNodeType(String nodeType) {
        if (nodeType == null || nodeType.isBlank()) {
            return null;
        }
        try {
            return IngestionNodeType.fromValue(nodeType);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
