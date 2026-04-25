
package com.rks.infra.util;

import java.util.regex.Pattern;

/**
 * LLM 响应清理工具类
 *
 * <p>
 * 提供对大语言模型（LLM）输出内容的清洗和规范化功能。
 * 主要处理 Markdown 代码块围栏的移除，使纯文本内容更加干净。
 * </p>
 *
 * <h2>主要功能</h2>
 * <ul>
 *   <li><b>去除代码块围栏</b>：移除 Markdown 代码块标记（如 ```json ... ```）</li>
 *   <li><b>空白字符处理</b>：trim 去除首尾空白</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * String raw = "```json\n{\"name\": \"test\"}\n```";
 * String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
 * // 结果: {"name": "test"}
 * }</pre>
 *
 * <h2>设计说明</h2>
 * <p>
 * 工具类采用 static 方法设计，无状态，可安全并行调用。
 * 使用正则表达式精确匹配各种 Markdown 代码块格式。
 * </p>
 */
public final class LLMResponseCleaner {

    /**
     * 匹配开头的代码块围栏：^``` 后跟可选的语言标识（如 json）和可选的换行
     * 例如：```json\n 或 ```\n 或 ```
     */
    private static final Pattern LEADING_CODE_FENCE = Pattern.compile("^```[\\w-]*\\s*\\n?");
    /**
     * 匹配结尾的代码块围栏：\n 后跟 ``` 和可选空白
     * 例如：\n``` 或 ```
     */
    private static final Pattern TRAILING_CODE_FENCE = Pattern.compile("\\n?```\\s*$");

    /**
     * 私有构造函数，防止实例化
     */
    private LLMResponseCleaner() {
    }

    /**
     * 移除 Markdown 代码块围栏（例如 ```json ... ```）
     *
     * <p>
     * 处理步骤：
     * </p>
     * <ol>
     *   <li>去除首尾空白</li>
     *   <li>移除开头的代码块围栏（如 ```json）</li>
     *   <li>移除结尾的代码块围栏（```）</li>
     *   <li>再次 trim 去除可能的残留空白</li>
     * </ol>
     *
     * @param raw 原始 LLM 输出内容
     * @return 清理后的纯内容，如果输入为 null 则返回 null
     */
    public static String stripMarkdownCodeFence(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.trim();
        // 移除开头的代码块围栏
        cleaned = LEADING_CODE_FENCE.matcher(cleaned).replaceFirst("");
        // 移除结尾的代码块围栏
        cleaned = TRAILING_CODE_FENCE.matcher(cleaned).replaceFirst("");
        return cleaned.trim();
    }
}
