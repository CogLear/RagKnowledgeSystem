package com.rks.rag.core.rewrite;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rks.rag.dao.entity.QueryTermMappingDO;
import com.rks.rag.dao.mapper.QueryTermMappingMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 查询术语归一化服务（Query Term Mapping）
 *
 * <p>功能说明：
 * 将用户查询中的非标准术语、缩写、口语等映射为知识库中的标准表述，
 * 解决"同义词检索"问题，提升检索召回率。
 *
 * <p>典型场景：
 * <pre>
 * 用户问："平安保险怎么退保"
 * 知识库中："退保流程" 章节
 * 映射规则：平安保司 → 平安保险
 * 归一化后："平安保险怎么退保" → 检索时能匹配到"退保流程"相关内容
 * </pre>
 *
 * <p>配置来源：
 * 术语映射规则存储在数据库表 {@code t_query_term_mapping}，
 * 通过管理员界面（QueryTermMappingController）进行增删改。
 *
 * <p>执行时机：
 * 在 QueryRewrite 流程中调用，位于子问题拆分之后、意图分类之前。
 * 每条规则仅执行一次子串匹配替换（match_type=1）。
 *
 * @see QueryTermMappingUtil#applyMapping
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryTermMappingService {

    private final QueryTermMappingMapper mappingMapper;

    /**
     * 缓存的映射规则列表
     *
     * <p>设计说明：
     * 使用 volatile 保证可见性，初始化后只读取不修改（更新需重启或手动刷新）。
     * 按优先级倒序、sourceTerm 长度倒序排列，确保：
     * <ul>
     *   <li>高优先级规则先执行</li>
     *   <li>长词优先匹配，避免短词"吃掉"长词的一部分</li>
     * </ul>
     */
    private volatile List<QueryTermMappingDO> cachedMappings = List.of();

    /**
     * 应用启动时加载映射规则到缓存
     *
     * <p>执行逻辑：
     * <ol>
     *   <li>从数据库查询所有 enabled=1 的规则</li>
     *   <li>按 priority 倒序、sourceTerm 长度倒序排序</li>
     *   <li>存入 cachedMappings 供后续使用</li>
     * </ol>
     *
     * <p>注意：
     * 当前实现为启动时一次性加载，后续更新规则需重启服务。
     * 如需热更新，可扩展为定时刷新或主动刷新接口。
     */
    @PostConstruct
    public void loadMappings() {
        List<QueryTermMappingDO> dbList = mappingMapper.selectList(
                Wrappers.lambdaQuery(QueryTermMappingDO.class)
                        .eq(QueryTermMappingDO::getEnabled, 1)
        );
        // 排序规则：优先级高的在前，sourceTerm 更长的在前
        // 原因：避免短词先替换把长词打断（如"保司"不该拆开"平安保险"）
        dbList.sort(Comparator
                .comparing(QueryTermMappingDO::getPriority, Comparator.nullsLast(Integer::compareTo)).reversed()
                .thenComparing(m -> m.getSourceTerm() == null ? 0 : m.getSourceTerm().length(), Comparator.reverseOrder())
        );
        cachedMappings = dbList;

        log.info("查询归一化映射规则加载完成, 共加载 {} 条规则", cachedMappings.size());
    }

    /**
     * 对用户问题做术语归一化
     *
     * <p>执行流程：
     * <ol>
     *   <li>遍历所有缓存的映射规则</li>
     *   <li>跳过不启用的规则（enabled != 1）</li>
     *   <li>跳过不支持的匹配类型（当前仅支持 match_type=1 子串匹配）</li>
     *   <li>调用 {@link QueryTermMappingUtil#applyMapping} 执行安全替换</li>
     *   <li>返回归一化后的文本</li>
     * </ol>
     *
     * <p>安全替换逻辑（参见 QueryTermMappingUtil）：
     * <ul>
     *   <li>若当前位置已是 targetTerm 开头（如"平安保险"），不重复替换</li>
     *   <li>仅做子串匹配替换，避免无限循环</li>
     * </ul>
     *
     * @param text 用户原始查询文本
     * @return 归一化后的文本（术语替换为标准表述）
     */
    public String normalize(String text) {
        if (text == null || text.isEmpty() || cachedMappings.isEmpty()) {
            return text;
        }
        String result = text;
        for (QueryTermMappingDO mapping : cachedMappings) {
            if (Boolean.FALSE.equals(mapping.getEnabled())) {
                continue;
            }
            if (mapping.getMatchType() != null && mapping.getMatchType() != 1) {
                // 这里只示例 match_type = 1 的简单子串匹配，其他类型可以自己扩展
                continue;
            }
            String source = mapping.getSourceTerm();
            String target = mapping.getTargetTerm();
            if (source == null || source.isEmpty() || target == null || target.isEmpty()) {
                continue;
            }
            result = QueryTermMappingUtil.applyMapping(result, source, target);
        }

        if (!Objects.equals(text, result)) {
            log.info("查询归一化：original='{}', normalized='{}'", text, result);
        }
        return result;
    }
}
