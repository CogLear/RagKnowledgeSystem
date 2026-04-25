
package com.rks.core.chunk.strategy;

import cn.hutool.core.util.IdUtil;

import com.rks.core.chunk.AbstractEmbeddingChunker;
import com.rks.core.chunk.ChunkingMode;
import com.rks.core.chunk.ChunkingOptions;
import com.rks.core.chunk.VectorChunk;
import com.rks.infra.embedding.EmbeddingClient;
import com.rks.infra.model.ModelSelector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定大小分块器
 * - 按 chunkSize 切分
 * - 相邻 chunk 保留 overlapSize 重叠
 * - 在边界符（换行/句末标点等）处向前对齐 end
 * 增强：
 * 1) 归一化：修复 URL 内“被换行拆开”的情况，但避免误吞段落换行/列表换行
 * 2) 英文 '.' 不再无条件当边界，避免切烂 URL 域名
 * 3) 边界回退距离 <= overlap（避免出现 chunk 几乎全重复）
 */
@Component
public class FixedSizeTextChunker extends AbstractEmbeddingChunker {

    public FixedSizeTextChunker(ModelSelector modelSelector, List<EmbeddingClient> embeddingClients) {
        super(modelSelector, embeddingClients);
    }

    @Override
    public ChunkingMode getType() {
        return ChunkingMode.FIXED_SIZE;
    }

    /**
     * 执行固定大小分块的核心逻辑
     *
     * <p>
     * 该方法是实际的分块执行逻辑，将长文本按固定大小切分成多个块。
     * 与简单的字符切分不同，该方法会在自然边界（换行、句末标点）处调整切分点，
     * 以保持语义的完整性。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>空文本检查</b>：空文本直接返回空列表</li>
     *   <li><b>文本归一化</b>：修复 URL 断行、中文软换行等问题</li>
     *   <li><b>参数校验</b>：确保 chunkSize 和 overlap 合法</li>
     *   <li><b>循环分块</b>：每次取 chunkSize 大小的文本，在边界处调整
     *     <ul>
     *       <li>查找最近的自然边界（换行、标点）</li>
     *       <li>确保回退距离不超过 overlap</li>
     *       <li>强制推进避免死循环</li>
     *     </ul>
     *   </li>
     *   <li><b>重叠处理</b>：相邻块之间保留 overlap 大小的重叠区域</li>
     * </ol>
     *
     * <h2>关键算法</h2>
     * <ul>
     *   <li><b>边界调整</b>：adjustToBoundary() 在 targetEnd 附近向前查找最近的自然边界</li>
     *   <li><b>回退限制</b>：回退距离不能超过 overlap，防止块之间高度重复</li>
     *   <li><b>强制推进</b>：如果边界调整导致 end <= start 或 end <= lastEnd，强制使用 targetEnd</li>
     * </ul>
     *
     * @param text 待分块的原始文本
     * @param config 分块配置参数
     * @return 分块后的 VectorChunk 列表
     */
    @Override
    protected List<VectorChunk> doChunk(String text, ChunkingOptions config) {
        // ========== 步骤1：空文本检查 ==========
        // 空文本或仅包含空白字符的文本返回空列表
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        // ========== 步骤2：文本归一化 ==========
        // 修复文本中的常见问题：
        // - 去掉 \r（Windows 换行符）
        // - 修复 URL 被换行拆开的问题（如 dingtalk.\ncom）
        // - 修复中文词中间软换行（如 商\n保通 → 商保通）
        // - 但保留正常的段落换行和列表项换行
        String normalized = normalizeText(text);

        // ========== 步骤3：参数校验 ==========
        // chunkSize: 块的目标大小，最小为 1
        // overlap: 相邻块之间的重叠大小，最小为 0
        int chunkSize = Math.max(1, config.getChunkSize());
        int overlap = Math.max(0, config.getOverlapSize());

        // ========== 特殊情况：chunkSize 为 MAX_VALUE ==========
        // 如果 chunkSize 设为最大值，说明不需要分块，整个文本作为一个块
        if (chunkSize == Integer.MAX_VALUE) {
            return List.of(VectorChunk.builder()
                    .chunkId(IdUtil.getSnowflakeNextIdStr())
                    .index(0)
                    .content(normalized)
                    .build());
        }

        // ========== overlap 上限校验 ==========
        // overlap 不能超过 chunkSize - 1（否则会出现块几乎全重复的情况）
        if (chunkSize > 1) {
            overlap = Math.min(overlap, chunkSize - 1);
        } else {
            overlap = 0;  // chunkSize 为 1 时，overlap 必须为 0
        }

        int len = normalized.length();
        List<VectorChunk> chunks = new ArrayList<>();

        // ========== 步骤4：循环分块 ==========
        // index: 当前块的序号
        // start: 当前块的起始位置
        // lastEnd: 上一个块的结束位置（用于检测死循环）
        int index = 0;
        int start = 0;
        int lastEnd = -1;

        // 当起始位置小于文本长度时，持续分块
        while (start < len) {
            // ========== 计算目标结束位置 ==========
            // targetEnd = start + chunkSize，但不超过文本长度
            int targetEnd = Math.min(start + chunkSize, len);

            // ========== 边界调整 ==========
            // 在 targetEnd 附近向前查找最近的自然边界
            // 优先使用换行符，其次是中文句末标点，再次是英文句末标点
            // 但回退距离不能超过 overlap
            int end = adjustToBoundary(normalized, start, targetEnd, overlap);

            // ========== 强制推进 ==========
            // 如果边界调整导致 end <= start（无法向前推进）
            // 或 end <= lastEnd（回到了上一个块的结束位置），强制使用 targetEnd
            // 这避免了由于边界调整算法导致的死循环
            if (end <= start || end <= lastEnd) {
                end = targetEnd;
            }

            // ========== 提取并保存块 ==========
            String content = normalized.substring(start, end);
            if (StringUtils.hasText(content.strip())) {
                chunks.add(VectorChunk.builder()
                        .chunkId(IdUtil.getSnowflakeNextIdStr())
                        .index(index++)
                        .content(content)
                        .build());
            }

            // ========== 更新状态 ==========
            lastEnd = end;  // 记录当前块的结束位置

            // 如果已经到达文本末尾，退出循环
            if (end >= len) break;

            // ========== 计算下一个块的起始位置 ==========
            // 下一个块的起始位置 = 当前块结束位置 - overlap（保持重叠）
            int nextStart = Math.max(0, end - overlap);

            // 如果计算出的 nextStart 不大于 start，说明 overlap 太大，强制推进
            if (nextStart <= start) nextStart = end;

            start = nextStart;  // 更新起始位置，继续下一轮分块
        }

        return chunks;
    }

    /**
     * 调整分块边界：
     * - 优先：换行
     * - 其次：中文句末标点（。！？）
     * - 再次：英文 .!?（仅当后面是空白/换行/结束 才算边界，避免切 URL 域名点号）
     * <p>
     * 回退距离 <= overlap，避免 chunk 高度重复。
     */
    private int adjustToBoundary(String text, int start, int targetEnd, int overlap) {
        if (targetEnd <= start) return targetEnd;

        int maxLookback = Math.min(overlap, targetEnd - start);
        if (maxLookback <= 0) return targetEnd;

        // 1) 换行
        for (int i = 0; i <= maxLookback; i++) {
            int pos = targetEnd - i - 1;
            if (pos <= start) break;
            if (text.charAt(pos) == '\n') return pos + 1;
        }

        // 2) 中文句末标点
        for (int i = 0; i <= maxLookback; i++) {
            int pos = targetEnd - i - 1;
            if (pos <= start) break;
            char c = text.charAt(pos);
            if (c == '。' || c == '！' || c == '？') return pos + 1;
        }

        // 3) 英文句末标点：后面必须是空白/换行/结束
        for (int i = 0; i <= maxLookback; i++) {
            int pos = targetEnd - i - 1;
            if (pos <= start) break;
            char c = text.charAt(pos);
            if (c == '.' || c == '!' || c == '?') {
                int next = pos + 1;
                if (next >= text.length()) return next;
                if (Character.isWhitespace(text.charAt(next))) return next;
            }
        }

        return targetEnd;
    }

    /**
     * 归一化输入：
     * - 去掉 \r
     * - 修复“URL 被换行拆开”的情况（比如 dingtalk.\ncom、/i/nodes\n/...）
     * - 但如果换行后是“2.” 这种列表项开头，绝不合并（避免吞段落）
     * - URL 结束时保留原始空白（包括空行）
     * - 修复中文词中间软换行（商\n保通 -> 商保通）
     */
    private String normalizeText(String text) {
        if (text == null || text.isEmpty()) return text;

        String src = text.replace("\r", "");
        StringBuilder out = new StringBuilder(src.length());

        boolean inUrl = false;

        for (int i = 0; i < src.length(); i++) {
            if (!inUrl && looksLikeUrlStart(src, i)) {
                inUrl = true;
            }

            char c = src.charAt(i);

            if (inUrl) {
                if (Character.isWhitespace(c)) {
                    int j = i;
                    boolean sawNewline = false;
                    while (j < src.length() && Character.isWhitespace(src.charAt(j))) {
                        if (src.charAt(j) == '\n') sawNewline = true;
                        j++;
                    }

                    char prev = (i > 0) ? src.charAt(i - 1) : 0;
                    char next = (j < src.length()) ? src.charAt(j) : 0;

                    // 只在“很像 URL 被拆开”的情况下合并空白
                    if (sawNewline && next != 0 && shouldJoinBrokenUrl(prev, next, src, j)) {
                        i = j - 1;
                        continue;
                    }

                    // URL 结束：保留原始空白（包括空行）
                    out.append(src, i, j);
                    inUrl = false;
                    i = j - 1;
                    continue;
                }

                out.append(c);

                // 遇到明显不可能属于 URL 的字符，退出 URL 状态
                if (!isUrlChar(c) && !isCommonUrlPunct(c)) {
                    inUrl = false;
                }
                continue;
            }

            // 非 URL 状态：修复中文词中间软换行（商\n保通 -> 商保通）
            if (c == '\n') {
                char prev = (i > 0) ? src.charAt(i - 1) : 0;
                char next = (i + 1 < src.length()) ? src.charAt(i + 1) : 0;

                if (isCjkWordChar(prev) && isCjkWordChar(next)) {
                    continue;
                }

                out.append('\n');
                continue;
            }

            out.append(c);
        }

        return out.toString();
    }

    /**
     * 判断：URL 内遇到换行/空白时，是否应该把空白删掉并继续拼接 URL
     * 关键：避免把 “\n2.”（列表项）吞掉。
     */
    private boolean shouldJoinBrokenUrl(char prev, char next, String s, int nextIndex) {
        // 如果下一行像 “2.” “10.” 这种列表项开头 -> 绝不合并
        if (isListItemStart(s, nextIndex)) {
            return false;
        }

        // 典型的 URL 断行场景：在这些字符后面换行，后续大概率还是 URL
        if (prev == '.' && Character.isLetter(next)) return true;                 // dingtalk.\ncom
        if (prev == '/' || prev == '?' || prev == '&' || prev == '='
                || prev == '#' || prev == '%' || prev == '-' || prev == '_'
                || prev == ':') return true;                                       // /i/nodes\n/...  ?\nutm=...

        // 或者下一段本身以 URL 结构符号开头
        if (next == '/' || next == '?' || next == '&' || next == '=' || next == '#') return true;

        // 其他情况更保守：不合并，保留换行
        return false;
    }

    private boolean isListItemStart(String s, int i) {
        // 跳过可能存在的空格/制表符（一般是新行后的缩进）
        int p = i;
        while (p < s.length() && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) p++;

        int start = p;
        while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
        if (p == start) return false;

        // 数字后紧跟 '.' 或 '）' / ')' 也常见
        if (p < s.length() && (s.charAt(p) == '.' || s.charAt(p) == '）' || s.charAt(p) == ')')) {
            return true;
        }
        return false;
    }

    private boolean looksLikeUrlStart(String s, int i) {
        if (i < 0 || i >= s.length()) return false;
        return s.startsWith("http://", i) || s.startsWith("https://", i);
    }

    private boolean isUrlChar(char c) {
        if (c >= 'a' && c <= 'z') return true;
        if (c >= 'A' && c <= 'Z') return true;
        if (c >= '0' && c <= '9') return true;

        return c == '-' || c == '.' || c == '_' || c == '~'
                || c == ':' || c == '/' || c == '?' || c == '#'
                || c == '[' || c == ']' || c == '@'
                || c == '!' || c == '$' || c == '&' || c == '\''
                || c == '(' || c == ')' || c == '*' || c == '+'
                || c == ',' || c == ';' || c == '=' || c == '%';
    }

    private boolean isCommonUrlPunct(char c) {
        return c == '.' || c == '/' || c == '?' || c == '&' || c == '=' || c == '-' || c == '_' || c == '%';
    }

    private boolean isCjkWordChar(char c) {
        if (c == 0) return false;
        if (Character.isWhitespace(c)) return false;
        if (!isCjkOrFullWidthLetterOrDigit(c)) return false;
        return !isCjkPunctuation(c);
    }

    private boolean isCjkOrFullWidthLetterOrDigit(char c) {
        if (c == 0) return false;
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    private boolean isCjkPunctuation(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.GENERAL_PUNCTUATION
                || c == '。' || c == '，' || c == '、' || c == '；' || c == '：'
                || c == '！' || c == '？' || c == '（' || c == '）' || c == '【' || c == '】'
                || c == '《' || c == '》' || c == '“' || c == '”' || c == '‘' || c == '’';
    }
}
