
package com.rks.rag.service.impl;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rks.framework.exception.ClientException;
import com.rks.rag.controller.request.QueryTermMappingCreateRequest;
import com.rks.rag.controller.request.QueryTermMappingPageRequest;
import com.rks.rag.controller.request.QueryTermMappingUpdateRequest;
import com.rks.rag.controller.vo.QueryTermMappingVO;
import com.rks.rag.core.rewrite.QueryTermMappingService;
import com.rks.rag.dao.entity.QueryTermMappingDO;
import com.rks.rag.dao.mapper.QueryTermMappingMapper;
import com.rks.rag.service.QueryTermMappingAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 查询词映射管理服务实现
 *
 * <p>
 * 负责查询词映射规则的 CRUD 操作，包括创建、查询、更新、删除。
 * 修改操作后会触发重新加载映射缓存。
 * </p>
 *
 * @see QueryTermMappingAdminService
 */
@Service
@RequiredArgsConstructor
public class QueryTermMappingAdminServiceImpl implements QueryTermMappingAdminService {

    /** 查询词映射 Mapper */
    private final QueryTermMappingMapper queryTermMappingMapper;
    /** 查询词映射服务（用于重新加载缓存） */
    private final QueryTermMappingService queryTermMappingService;

    /**
     * 创建查询词映射规则
     *
     * @param requestParam 映射规则（源词、目标词、匹配类型、优先级等）
     * @return 新建规则的ID
     */
    @Override
    public String create(QueryTermMappingCreateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String sourceTerm = StrUtil.trimToNull(requestParam.getSourceTerm());
        String targetTerm = StrUtil.trimToNull(requestParam.getTargetTerm());
        Assert.notBlank(sourceTerm, () -> new ClientException("原始词不能为空"));
        Assert.notBlank(targetTerm, () -> new ClientException("目标词不能为空"));

        QueryTermMappingDO record = new QueryTermMappingDO();
        record.setSourceTerm(sourceTerm);
        record.setTargetTerm(targetTerm);
        record.setMatchType(requestParam.getMatchType() != null ? requestParam.getMatchType() : 1);
        record.setPriority(requestParam.getPriority() != null ? requestParam.getPriority() : 0);
        record.setEnabled(requestParam.getEnabled() != null ? requestParam.getEnabled() : true);
        record.setRemark(StrUtil.trimToNull(requestParam.getRemark()));

        queryTermMappingMapper.insert(record);
        queryTermMappingService.loadMappings();
        return String.valueOf(record.getId());
    }

    /**
     * 更新查询词映射规则
     *
     * @param id           规则ID
     * @param requestParam 更新内容
     */
    @Override
    public void update(String id, QueryTermMappingUpdateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        QueryTermMappingDO record = loadById(id);

        if (requestParam.getSourceTerm() != null) {
            String sourceTerm = StrUtil.trimToNull(requestParam.getSourceTerm());
            Assert.notBlank(sourceTerm, () -> new ClientException("原始词不能为空"));
            record.setSourceTerm(sourceTerm);
        }
        if (requestParam.getTargetTerm() != null) {
            String targetTerm = StrUtil.trimToNull(requestParam.getTargetTerm());
            Assert.notBlank(targetTerm, () -> new ClientException("目标词不能为空"));
            record.setTargetTerm(targetTerm);
        }
        if (requestParam.getMatchType() != null) {
            record.setMatchType(requestParam.getMatchType());
        }
        if (requestParam.getPriority() != null) {
            record.setPriority(requestParam.getPriority());
        }
        if (requestParam.getEnabled() != null) {
            record.setEnabled(requestParam.getEnabled());
        }
        if (requestParam.getRemark() != null) {
            record.setRemark(StrUtil.trimToNull(requestParam.getRemark()));
        }

        queryTermMappingMapper.updateById(record);
        queryTermMappingService.loadMappings();
    }

    /**
     * 删除查询词映射规则
     *
     * @param id 规则ID
     */
    @Override
    public void delete(String id) {
        QueryTermMappingDO record = loadById(id);
        queryTermMappingMapper.deleteById(record.getId());
        queryTermMappingService.loadMappings();
    }

    /**
     * 根据ID查询映射规则
     *
     * @param id 规则ID
     * @return 规则详情
     */
    @Override
    public QueryTermMappingVO queryById(String id) {
        QueryTermMappingDO record = loadById(id);
        return toVO(record);
    }

    /**
     * 分页查询映射规则列表
     *
     * <p>
     * 支持按源词或目标词关键字模糊搜索，
     * 按优先级升序、更新时间倒序排列。
     * </p>
     *
     * @param requestParam 分页和搜索参数
     * @return 规则分页结果
     */
    @Override
    public IPage<QueryTermMappingVO> pageQuery(QueryTermMappingPageRequest requestParam) {
        String keyword = StrUtil.trimToNull(requestParam.getKeyword());
        Page<QueryTermMappingDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<QueryTermMappingDO> result = queryTermMappingMapper.selectPage(
                page,
                Wrappers.lambdaQuery(QueryTermMappingDO.class)
                        .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                                .like(QueryTermMappingDO::getSourceTerm, keyword)
                                .or()
                                .like(QueryTermMappingDO::getTargetTerm, keyword))
                        .orderByAsc(QueryTermMappingDO::getPriority)
                        .orderByDesc(QueryTermMappingDO::getUpdateTime)
        );
        return result.convert(this::toVO);
    }

    private QueryTermMappingDO loadById(String id) {
        Long parsedId = parseId(id);
        QueryTermMappingDO record = queryTermMappingMapper.selectById(parsedId);
        Assert.notNull(record, () -> new ClientException("映射规则不存在"));
        return record;
    }

    private Long parseId(String id) {
        if (StrUtil.isBlank(id)) {
            throw new ClientException("映射规则ID不能为空");
        }
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException ex) {
            throw new ClientException("映射规则ID非法");
        }
    }

    private QueryTermMappingVO toVO(QueryTermMappingDO record) {
        return QueryTermMappingVO.builder()
                .id(String.valueOf(record.getId()))
                .sourceTerm(record.getSourceTerm())
                .targetTerm(record.getTargetTerm())
                .matchType(record.getMatchType())
                .priority(record.getPriority())
                .enabled(record.getEnabled())
                .remark(record.getRemark())
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }
}
