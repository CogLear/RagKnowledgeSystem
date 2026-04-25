
package com.rks.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.rks.rag.controller.request.SampleQuestionCreateRequest;
import com.rks.rag.controller.request.SampleQuestionPageRequest;
import com.rks.rag.controller.request.SampleQuestionUpdateRequest;
import com.rks.rag.controller.vo.SampleQuestionVO;


import java.util.List;

/**
 * 示例问题服务接口
 *
 * <p>
 * 负责示例问题的 CRUD 操作，包括创建、查询、更新、删除和随机获取。
 * 示例问题用于在对话界面随机展示给用户参考。
 * </p>
 *
 * @see com.rks.rag.service.impl.SampleQuestionServiceImpl
 */
public interface SampleQuestionService {

    /**
     * 创建示例问题
     *
     * @param requestParam 创建参数（标题、描述、问题内容）
     * @return 新建问题的ID
     */
    String create(SampleQuestionCreateRequest requestParam);

    /**
     * 更新示例问题
     *
     * @param id           问题ID
     * @param requestParam 更新内容（标题、描述、问题内容）
     */
    void update(String id, SampleQuestionUpdateRequest requestParam);

    /**
     * 删除示例问题
     *
     * @param id 问题ID
     */
    void delete(String id);

    /**
     * 查询示例问题详情
     *
     * @param id 问题ID
     * @return 问题详情
     */
    SampleQuestionVO queryById(String id);

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
    IPage<SampleQuestionVO> pageQuery(SampleQuestionPageRequest requestParam);

    /**
     * 随机获取示例问题
     *
     * <p>
     * 从数据库中随机抽取指定数量的问题，用于展示给用户。
     * </p>
     *
     * @return 随机问题列表
     */
    List<SampleQuestionVO> listRandomQuestions();
}
