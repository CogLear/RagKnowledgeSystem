
package com.rks.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.rks.framework.convention.Result;
import com.rks.framework.web.Results;
import com.rks.rag.controller.request.QueryTermMappingCreateRequest;
import com.rks.rag.controller.request.QueryTermMappingPageRequest;
import com.rks.rag.controller.request.QueryTermMappingUpdateRequest;
import com.rks.rag.controller.vo.QueryTermMappingVO;
import com.rks.rag.service.QueryTermMappingAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 关键词映射管理控制器
 */
@RestController
@RequiredArgsConstructor
public class QueryTermMappingController {

    private final QueryTermMappingAdminService queryTermMappingAdminService;

    /**
     * 分页查询关键词映射规则
     *
     * <p>
     * 返回系统中配置的关键词映射规则列表。
     * 映射规则用于将用户 query 中的同义词/缩写映射到标准术语。
     * </p>
     *
     * @param requestParam 分页和过滤参数
     * @return 映射规则分页结果
     */
    @GetMapping("/mappings")
    public Result<IPage<QueryTermMappingVO>> pageQuery(QueryTermMappingPageRequest requestParam) {
        return Results.success(queryTermMappingAdminService.pageQuery(requestParam));
    }

    /**
     * 查询映射规则详情
     *
     * @param id 映射规则ID
     * @return 映射规则详情
     */
    @GetMapping("/mappings/{id}")
    public Result<QueryTermMappingVO> queryById(@PathVariable String id) {
        return Results.success(queryTermMappingAdminService.queryById(id));
    }

    /**
     * 创建关键词映射规则
     *
     * <p>
     * 添加新的同义词或术语映射关系。
     * 例如："AI" → "人工智能"，"ML" → "机器学习"。
     * </p>
     *
     * @param requestParam 映射规则配置（源词、目标词、知识库关联等）
     * @return 新建规则的ID
     */
    @PostMapping("/mappings")
    public Result<String> create(@RequestBody QueryTermMappingCreateRequest requestParam) {
        return Results.success(queryTermMappingAdminService.create(requestParam));
    }

    /**
     * 更新映射规则
     *
     * @param id           规则ID
     * @param requestParam 新的规则配置
     * @return 空结果
     */
    @PutMapping("/mappings/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody QueryTermMappingUpdateRequest requestParam) {
        queryTermMappingAdminService.update(id, requestParam);
        return Results.success();
    }

    /**
     * 删除映射规则
     *
     * @param id 规则ID
     * @return 空结果
     */
    @DeleteMapping("/mappings/{id}")
    public Result<Void> delete(@PathVariable String id) {
        queryTermMappingAdminService.delete(id);
        return Results.success();
    }
}
