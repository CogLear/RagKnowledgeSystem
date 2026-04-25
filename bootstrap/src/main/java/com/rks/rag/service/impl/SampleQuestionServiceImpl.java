
package com.rks.rag.service.impl;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rks.framework.exception.ClientException;
import com.rks.rag.controller.request.SampleQuestionCreateRequest;
import com.rks.rag.controller.request.SampleQuestionPageRequest;
import com.rks.rag.controller.request.SampleQuestionUpdateRequest;
import com.rks.rag.controller.vo.SampleQuestionVO;
import com.rks.rag.dao.entity.SampleQuestionDO;
import com.rks.rag.dao.mapper.SampleQuestionMapper;
import com.rks.rag.service.SampleQuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 示例问题服务实现
 *
 * <p>
 * 负责示例问题的 CRUD 操作，包括创建、查询、更新、删除和随机获取。
 * 示例问题用于在对话界面随机展示给用户参考。
 * </p>
 *
 * @see SampleQuestionService
 */
@Service
@RequiredArgsConstructor
public class SampleQuestionServiceImpl implements SampleQuestionService {

    /** 随机获取的默认问题数量 */
    private static final int DEFAULT_LIMIT = 3;

    /** 示例问题 Mapper */
    private final SampleQuestionMapper sampleQuestionMapper;

    /**
     * 创建示例问题
     *
     * @param requestParam 创建参数（标题、描述、问题内容）
     * @return 新建问题的ID
     */
    @Override
    public String create(SampleQuestionCreateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String question = StrUtil.trimToNull(requestParam.getQuestion());
        Assert.notBlank(question, () -> new ClientException("示例问题内容不能为空"));

        SampleQuestionDO record = SampleQuestionDO.builder()
                .title(StrUtil.trimToNull(requestParam.getTitle()))
                .description(StrUtil.trimToNull(requestParam.getDescription()))
                .question(question)
                .build();
        sampleQuestionMapper.insert(record);
        return String.valueOf(record.getId());
    }

    /**
     * 更新示例问题
     *
     * @param id           问题ID
     * @param requestParam 更新内容（标题、描述、问题内容）
     */
    @Override
    public void update(String id, SampleQuestionUpdateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        SampleQuestionDO record = loadById(id);

        if (requestParam.getQuestion() != null) {
            String question = StrUtil.trimToNull(requestParam.getQuestion());
            Assert.notBlank(question, () -> new ClientException("示例问题内容不能为空"));
            record.setQuestion(question);
        }
        if (requestParam.getTitle() != null) {
            record.setTitle(StrUtil.trimToNull(requestParam.getTitle()));
        }
        if (requestParam.getDescription() != null) {
            record.setDescription(StrUtil.trimToNull(requestParam.getDescription()));
        }

        sampleQuestionMapper.updateById(record);
    }

    /**
     * 删除示例问题
     *
     * @param id 问题ID
     */
    @Override
    public void delete(String id) {
        SampleQuestionDO record = loadById(id);
        sampleQuestionMapper.deleteById(record.getId());
    }

    /**
     * 根据ID查询示例问题
     *
     * @param id 问题ID
     * @return 问题详情
     */
    @Override
    public SampleQuestionVO queryById(String id) {
        SampleQuestionDO record = loadById(id);
        return toVO(record);
    }

    /**
     * 分页查询示例问题列表
     *
     * <p>
     * 支持按标题、描述、问题内容关键字模糊搜索，
     * 按更新时间倒序排列。
     * </p>
     *
     * @param requestParam 分页和搜索参数
     * @return 问题分页结果
     */
    @Override
    public IPage<SampleQuestionVO> pageQuery(SampleQuestionPageRequest requestParam) {
        String keyword = StrUtil.trimToNull(requestParam.getKeyword());
        Page<SampleQuestionDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<SampleQuestionDO> result = sampleQuestionMapper.selectPage(
                page,
                Wrappers.lambdaQuery(SampleQuestionDO.class)
                        .eq(SampleQuestionDO::getDeleted, 0)
                        .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                                .like(SampleQuestionDO::getTitle, keyword)
                                .or()
                                .like(SampleQuestionDO::getDescription, keyword)
                                .or()
                                .like(SampleQuestionDO::getQuestion, keyword))
                        .orderByDesc(SampleQuestionDO::getUpdateTime)
        );
        return result.convert(this::toVO);
    }

    /**
     * 随机获取示例问题
     *
     * <p>
     * 从数据库中随机抽取指定数量的问题，用于展示给用户。
     * </p>
     *
     * @return 随机问题列表
     */
    @Override
    public List<SampleQuestionVO> listRandomQuestions() {
        List<SampleQuestionDO> records = sampleQuestionMapper.selectList(
                Wrappers.lambdaQuery(SampleQuestionDO.class)
                        .eq(SampleQuestionDO::getDeleted, 0)
                        .last("ORDER BY RAND() LIMIT " + DEFAULT_LIMIT)
        );
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * 根据ID加载示例问题
     *
     * @param id 问题ID
     * @return 问题实体
     * @throws ClientException 问题不存在时抛出
     */
    private SampleQuestionDO loadById(String id) {
        Long parsedId = parseId(id);
        SampleQuestionDO record = sampleQuestionMapper.selectOne(
                Wrappers.lambdaQuery(SampleQuestionDO.class)
                        .eq(SampleQuestionDO::getId, parsedId)
                        .eq(SampleQuestionDO::getDeleted, 0)
        );
        Assert.notNull(record, () -> new ClientException("示例问题不存在"));
        return record;
    }

    private Long parseId(String id) {
        if (StrUtil.isBlank(id)) {
            throw new ClientException("示例问题ID不能为空");
        }
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException ex) {
            throw new ClientException("示例问题ID非法");
        }
    }

    private SampleQuestionVO toVO(SampleQuestionDO record) {
        return SampleQuestionVO.builder()
                .id(String.valueOf(record.getId()))
                .title(record.getTitle())
                .description(record.getDescription())
                .question(record.getQuestion())
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }
}
