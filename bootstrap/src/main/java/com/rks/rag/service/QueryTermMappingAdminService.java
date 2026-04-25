package com.rks.rag.service;


import com.baomidou.mybatisplus.core.metadata.IPage;
import com.rks.rag.controller.request.QueryTermMappingCreateRequest;
import com.rks.rag.controller.request.QueryTermMappingPageRequest;
import com.rks.rag.controller.request.QueryTermMappingUpdateRequest;
import com.rks.rag.controller.vo.QueryTermMappingVO;


/**
 * 查询词映射管理服务接口
 *
 * <p>
 * 负责查询词映射规则的 CRUD 操作，包括创建、查询、更新、删除。
 * 修改操作后会触发重新加载映射缓存。
 * </p>
 *
 * @see com.rks.rag.service.impl.QueryTermMappingAdminServiceImpl
 */
public interface QueryTermMappingAdminService {

    /**
     * 创建查询词映射规则
     *
     * @param requestParam 映射规则（源词、目标词、匹配类型、优先级等）
     * @return 新建规则的ID
     */
    String create(QueryTermMappingCreateRequest requestParam);

    /**
     * 更新查询词映射规则
     *
     * @param id           规则ID
     * @param requestParam 更新内容
     */
    void update(String id, QueryTermMappingUpdateRequest requestParam);

    /**
     * 删除查询词映射规则
     *
     * @param id 规则ID
     */
    void delete(String id);

    /**
     * 查询映射规则详情
     *
     * @param id 规则ID
     * @return 规则详情
     */
    QueryTermMappingVO queryById(String id);

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
    IPage<QueryTermMappingVO> pageQuery(QueryTermMappingPageRequest requestParam);
}