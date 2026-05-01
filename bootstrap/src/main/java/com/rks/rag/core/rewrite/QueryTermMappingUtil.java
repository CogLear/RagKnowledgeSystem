
package com.rks.rag.core.rewrite;

/**
 * 查询术语归一化的替换工具类
 *
 * <p>核心能力：安全执行子串替换，避免重复替换和错误替换。
 *
 * <p>为什么需要安全替换：
 * <pre>
 * 假设文本：”平安保险退保流程”
 * 映射规则：平安保司 → 平安保险
 *
 * 普通替换（错误）：”平安保险保险退保流程” （”保司”被替换后，”保险”重复了）
 * 安全替换（正确）：”平安保险退保流程”     （检查到已是目标词开头，跳过）
 * </pre>
 *
 * <p>工作原理：
 * <ol>
 *   <li>从头到尾扫描文本，查找 sourceTerm 出现位置</li>
 *   <li>命中前拷贝原文本</li>
 *   <li>命中时检查：当前位置是否已是 targetTerm 开头？
 *     <ul>
 *       <li>是 → 直接拷贝原文中的 targetTerm，一次性跳过</li>
 *       <li>否 → 执行替换，拷贝 targetTerm</li>
 *     </ul>
 *   </li>
 *   <li>继续扫描后续文本</li>
 * </ol>
 */
public class QueryTermMappingUtil {

    /**
     * 安全归一化替换：只替换 sourceTerm，如果当前位置本身已经是 targetTerm 起始则跳过
     *
     * <p>示例：
     * <pre>
     * text = "平安保险退保流程"
     * sourceTerm = "保司"
     * targetTerm = "保险"
     *
     * 执行过程：
     * 1. 找到"保司"在位置3
     * 2. 检查 text[3:3+2] = "保险"，已是以"保险"开头
     * 3. 跳过，不替换
     * 4. 最终返回原文，不改变
     * </pre>
     *
     * @param text        原始文本
     * @param sourceTerm  待替换的源术语
     * @param targetTerm  目标术语
     * @return 替换后的文本
     */
    public static String applyMapping(String text, String sourceTerm, String targetTerm) {
        if (text == null || text.isEmpty() || sourceTerm == null || sourceTerm.isEmpty()) {
            return text;
        }

        StringBuilder sb = new StringBuilder();
        int idx = 0;
        int len = text.length();
        int sourceLen = sourceTerm.length();
        int targetLen = targetTerm.length();

        while (idx < len) {
            int hit = text.indexOf(sourceTerm, idx);
            if (hit < 0) {
                // 后面没有命中，整体拷贝
                sb.append(text, idx, len);
                break;
            }

            // 先把命中之前的文本拷贝过去
            sb.append(text, idx, hit);

            // 判断当前位置是否已经是 targetTerm 的开头
            boolean alreadyTarget =
                    targetTerm != null
                            && hit + targetLen <= len
                            && text.startsWith(targetTerm, hit);

            if (alreadyTarget) {
                // 已经是目标词开头了，直接按原文拷贝 targetTerm，一次性跳过
                sb.append(text, hit, hit + targetLen);
                idx = hit + targetLen;
            } else {
                // 不是目标词开头，正常做归一化替换
                sb.append(targetTerm);
                idx = hit + sourceLen;
            }
        }

        return sb.toString();
    }
}
