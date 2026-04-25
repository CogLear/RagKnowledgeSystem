
package com.rks.ingestion.node;

import com.rks.framework.exception.ClientException;
import com.rks.ingestion.domain.context.DocumentSource;
import com.rks.ingestion.domain.context.IngestionContext;
import com.rks.ingestion.domain.enums.IngestionNodeType;
import com.rks.ingestion.domain.enums.SourceType;
import com.rks.ingestion.domain.pipeline.NodeConfig;
import com.rks.ingestion.domain.result.NodeResult;
import com.rks.ingestion.strategy.fetcher.DocumentFetcher;
import com.rks.ingestion.strategy.fetcher.FetchResult;
import com.rks.ingestion.util.MimeTypeDetector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文档获取节点 (Fetcher Node)
 * 数据摄取负责从多元化的存储介质（如 Local FS、HTTP/HTTPS、OSS 等）中检索并载入文档原始字节流
 * 核心逻辑采用策略模式 (Strategy Pattern) 实现，根据 {@link SourceType} 动态路由至具体的 {@link DocumentFetcher}
 * 具备幂等性检查机制：若上下文中已预置原始字节，则自动跳过获取流程，避免重复 I/O
 */
@Component
public class FetcherNode implements IngestionNode {

    private final Map<SourceType, DocumentFetcher> fetchers;

    public FetcherNode(List<DocumentFetcher> fetchers) {
        this.fetchers = fetchers.stream()
                .collect(Collectors.toMap(DocumentFetcher::supportedType, Function.identity()));
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.FETCHER.getValue();
    }

    /**
     * 执行文档获取逻辑
     *
     * <p>
     * FetcherNode 是流水线的第一个节点，负责从各种数据源获取文档原始字节。
     * 核心采用策略模式，根据 {@link SourceType} 动态路由到对应的 {@link DocumentFetcher} 实现。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>幂等检查</b>：如果 context 中已存在 rawBytes（外部预加载），直接跳过获取流程</li>
     *   <li><b>来源校验</b>：确保 DocumentSource 存在且类型非空</li>
     *   <li><b>获取路由</b>：根据 source.getType() 从 fetcher 映射中查找对应的 Fetcher</li>
     *   <li><b>执行获取</b>：调用 fetcher.fetch(source) 获取文档内容</li>
     *   <li><b>结果回填</b>：将获取到的字节数组、MIME 类型、文件名存入 context</li>
     * </ol>
     *
     * <h2>支持的来源类型</h2>
     * <ul>
     *   <li>LOCAL_FILE - 本地文件系统</li>
     *   <li>HTTP_URL - HTTP/HTTPS URL</li>
     *   <li>S3 - S3 兼容对象存储</li>
     *   <li>FEISHU - 飞书文档</li>
     * </ul>
     *
     * <h2>流水线数据传递</h2>
     * <table border="1" cellpadding="5">
     *   <tr><th>方向</th><th>字段</th><th>说明</th></tr>
     *   <tr><td>【读取】</td><td>context.source</td><td>文档来源信息（类型、地址等）</td></tr>
     *   <tr><td>【读取】</td><td>context.rawBytes</td><td>已预加载的字节（幂等跳过用）</td></tr>
     *   <tr><td>【写入】</td><td>context.rawBytes</td><td>文档原始字节数组</td></tr>
     *   <tr><td>【写入】</td><td>context.mimeType</td><td>检测到的 MIME 类型</td></tr>
     *   <tr><td>【回填】</td><td>source.fileName</td><td>文件名（从 fetcher 返回回填）</td></tr>
     * </table>
     *
     * <h2>流水线位置</h2>
     * <pre>
     * [Source] → 【Fetcher】 → [rawBytes, mimeType] → Parser
     * </pre>
     *
     * @param context 摄取上下文，包含文档来源信息和存储结果的容器
     * @param config  节点配置（当前实现未使用）
     * @return 获取结果，成功时包含字节数，失败时包含错误信息
     * @see DocumentFetcher
     * @see FetchResult
     */
    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        // ========== 步骤1：幂等检查 ==========
        // 如果外部已经预加载了 rawBytes（例如 S3 批量导入场景），直接跳过获取流程
        if (context.getRawBytes() != null && context.getRawBytes().length > 0) {
            // 仅当 mimeType 缺失时，通过字节流和文件名进行检测
            if (!StringUtils.hasText(context.getMimeType())) {
                String fileName = context.getSource() == null ? null : context.getSource().getFileName();
                context.setMimeType(MimeTypeDetector.detect(context.getRawBytes(), fileName));
            }
            return NodeResult.ok("已跳过获取器：原始字节已存在");
        }

        // ========== 步骤2：来源校验 ==========
        // 确保 DocumentSource 存在且类型非空
        DocumentSource source = context.getSource();
        if (source == null || source.getType() == null) {
            return NodeResult.fail(new ClientException("文档来源不能为空"));
        }

        // ========== 步骤3：获取路由 ==========
        // 根据 source.getType() 从预建的 fetcher 映射中查找对应的 DocumentFetcher 实现
        // 策略模式：根据来源类型自动路由到对应的获取器（LocalFileFetcher / HttpUrlFetcher / S3Fetcher / FeishuFetcher）
        DocumentFetcher fetcher = fetchers.get(source.getType());
        if (fetcher == null) {
            return NodeResult.fail(new ClientException("不支持的来源类型: " + source.getType()));
        }

        // ========== 步骤4：执行获取 ==========
        // 调用具体的 Fetcher 实现获取文档内容（字节数组、MIME类型、文件名）
        FetchResult result = fetcher.fetch(source);

        // ========== 步骤5：结果回填 ==========
        // ① 将文档原始字节数组写入 context，传递给下一个节点（Parser）
        context.setRawBytes(result.content());
        // ② 如果 fetcher 返回了 MIME 类型，写入 context
        if (StringUtils.hasText(result.mimeType())) {
            context.setMimeType(result.mimeType());
        }
        // ③ 如果 fetcher 解析出了文件名，回填到 source 对象（用于后续节点使用）
        if (StringUtils.hasText(result.fileName())) {
            source.setFileName(result.fileName());
        }
        // 返回成功结果，包含获取的字节数
        return NodeResult.ok("已获取 " + (result.content() == null ? 0 : result.content().length) + " 字节");
    }
}
