
package com.rks.core.parser;

import com.rks.framework.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;

/**
 * Apache Tika 文档解析器
 * <p>
 * 支持多种文档格式：PDF、Word、Excel、PPT、HTML、XML 等
 * 使用 Apache Tika 库进行文档解析和文本提取
 */
@Slf4j
@Component
public class TikaDocumentParser implements DocumentParser {

    private static final Tika TIKA = new Tika();

    @Override
    public String getParserType() {
        return ParserType.TIKA.getType();
    }

    /**
     * 解析文档字节数组
     *
     * <p>
     * 使用 Apache Tika 将文档的二进制内容解析为纯文本。
     * Tika 支持多种文档格式的自动检测和解析。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>空检查</b>：空内容返回空文本结果</li>
     *   <li><b>流创建</b>：将字节数组包装为 ByteArrayInputStream</li>
     *   <li><b>Tika 解析</b>：调用 Tika.parseToString() 提取文本</li>
     *   <li><b>文本清理</b>：使用 TextCleanupUtil 清理无用字符和格式</li>
     *   <li><b>结果封装</b>：返回包含纯文本的 ParseResult</li>
     * </ol>
     *
     * <h2>支持的格式</h2>
     * <ul>
     *   <li>PDF（application/pdf）</li>
     *   <li>Word（application/msword, application/vnd.openxmlformats-officedocument.wordprocessingml.document）</li>
     *   <li>Excel（application/vnd.ms-excel, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet）</li>
     *   <li>PPT（application/vnd.ms-powerpoint, application/vnd.openxmlformats-officedocument.presentationml.presentation）</li>
     *   <li>HTML/XML（text/html, application/xml）</li>
     *   <li>纯文本（text/plain）</li>
     *   <li>以及更多...</li>
     * </ul>
     *
     * <h2>异常处理</h2>
     * <ul>
     *   <li>解析失败时记录日志，包含 MIME 类型信息</li>
     *   <li>抛出 ServiceException 中断流程</li>
     * </ul>
     *
     * @param content 文档的二进制字节数组
     * @param mimeType 文档的 MIME 类型（用于日志记录）
     * @param options 解析选项（当前版本未使用）
     * @return 解析结果，包含纯文本和空元数据
     */
    @Override
    public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        // ========== 步骤1：空检查 ==========
        // 空内容直接返回空文本结果，避免后续解析报错
        if (content == null || content.length == 0) {
            return ParseResult.ofText("");
        }

        try (ByteArrayInputStream is = new ByteArrayInputStream(content)) {
            // ========== 步骤2：Tika 解析 ==========
            // Tika.parseToString() 会自动检测文档格式并提取文本
            // 支持的格式由 Tika 内部配置决定
            String text = TIKA.parseToString(is);

            // ========== 步骤3：文本清理 ==========
            // 使用 TextCleanupUtil 清理提取的文本
            // 可能包括：去除多余空白、修复编码问题、移除无用字符等
            String cleaned = TextCleanupUtil.cleanup(text);

            // ========== 步骤4：结果封装 ==========
            // 返回只包含文本的解析结果（元数据为空 Map）
            return ParseResult.ofText(cleaned);

        } catch (Exception e) {
            // ========== 异常处理 ==========
            // 记录详细错误日志，包括 MIME 类型信息
            log.error("Tika 解析失败，MIME 类型: {}", mimeType, e);

            // 抛出 ServiceException，向上传递错误
            throw new ServiceException("文档解析失败: " + e.getMessage());
        }
    }

    @Override
    public String extractText(InputStream stream, String fileName) {
        try {
            String text = TIKA.parseToString(stream);
            return TextCleanupUtil.cleanup(text);
        } catch (Exception e) {
            log.error("从文件中提取文本内容失败: {}", fileName, e);
            throw new ServiceException("解析文件失败: " + fileName);
        }
    }

    @Override
    public boolean supports(String mimeType) {
        // Tika 支持大部分常见文档格式
        return mimeType != null && !mimeType.startsWith("text/markdown");
    }
}
