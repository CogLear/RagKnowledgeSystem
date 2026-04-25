package com.rks.infra.token;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 启发式 Token 估算服务实现
 *
 * <p>
 * 提供一种轻量级的文本 Token 数量估算方法，无需调用外部 API。
 * 该实现基于对多种语言字符的统计规律进行估算：
 * </p>
 * <ul>
 *   <li><b>ASCII 字符（英文、数字、标点）</b>：每 4 个字符约 1 个 Token</li>
 *   <li><b>CJK 字符（中文、日文、韩文）</b>：每个字符约 1 个 Token</li>
 *   <li><b>其他字符</b>：每 2 个字符约 1 个 Token</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <p>
 * 适用于快速估算成本、限制输入长度、监控 Token 消耗等场景。
 * 如果需要精确计数，应使用 Tiktoken 等基于 BPE 的库。
 * </p>
 *
 * <h2>注意事项</h2>
 * <ul>
 *   <li>空格不计入 Token</li>
 *   <li>估算结果为近似值，与实际模型 Tokenizer 有偏差</li>
 *   <li>特殊符号和 Emoji 等按"其他字符"计算</li>
 * </ul>
 *
 * @see TokenCounterService
 */
@Service
public class HeuristicTokenCounterService implements TokenCounterService {

    @Override
    public Integer countTokens(String text) {
        // 空文本或纯空白返回 0
        if (!StringUtils.hasText(text)) {
            return 0;
        }

        int asciiCount = 0;  // ASCII 字符计数（英文、数字、标点）
        int cjkCount = 0;    // CJK 字符计数（中文、日文、韩文）
        int otherCount = 0;  // 其他字符计数

        // 遍历每个字符，分类统计
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            // 空格、制表符、换行符等不计入 Token
            if (Character.isWhitespace(ch)) {
                continue;
            }
            // ASCII 范围（0x00-0x7F）：包括英文字母、数字、常用标点
            if (ch <= 0x7F) {
                asciiCount++;
            } else if (isCjk(ch)) {
                // CJK 统一表意文字、日文假名、韩文字母
                cjkCount++;
            } else {
                // 其他字符：俄语字母、阿拉伯字母、Emoji 等
                otherCount++;
            }
        }

        // 估算 Token 数量：
        // ASCII：每 4 个字符约 1 个 Token（向上取整）
        int asciiTokens = (asciiCount + 3) / 4;
        // 其他字符：每 2 个字符约 1 个 Token（向上取整）
        int otherTokens = (otherCount + 1) / 2;
        // CJK：每个字符约 1 个 Token（直接计数）

        int total = asciiTokens + cjkCount + otherTokens;
        // 最少返回 1 个 Token（即使是空文本也会返回 0）
        return Math.max(total, 1);
    }

    /**
     * 判断字符是否为 CJK（中日韩）字符
     * <p>
     * 检查 Unicode 区块，包括：
     * <ul>
     *   <li>CJK 统一表意文字（基本及扩展 B~F）</li>
     *   <li>CJK 兼容表意文字（补充）</li>
     *   <li>CJK 笔画、符号和标点</li>
     *   <li>日文平假名、片假名及扩展</li>
     *   <li>韩文字母、音节及兼容字母</li>
     * </ul>
     */
    private boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT
                || block == Character.UnicodeBlock.CJK_RADICALS_SUPPLEMENT
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO;
    }
}
