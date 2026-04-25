
package com.rks.knowledge.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.rks.framework.convention.Result;
import com.rks.framework.web.Results;
import com.rks.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.rks.knowledge.controller.request.KnowledgeBasePageRequest;
import com.rks.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.rks.knowledge.controller.vo.KnowledgeBaseVO;
import com.rks.knowledge.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 知识库控制器
 * 提供知识库的增删改查等基础操作接口
 */
@RestController
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * 创建知识库
     *
     * <p>
     * 创建一个新的知识库，用于组织和管理相关文档。
     * 知识库创建后可以上传文档并进行向量化检索。
     * </p>
     *
     * @param requestParam 包含知识库名称等配置信息
     * @return 新建知识库的ID
     */
    @PostMapping("/knowledge-base")
    public Result<String> createKnowledgeBase(@RequestBody KnowledgeBaseCreateRequest requestParam) {
        StpUtil.checkRole("admin");
        return Results.success(knowledgeBaseService.create(requestParam));
    }

    /**
     * 重命名知识库
     *
     * <p>
     * 修改指定知识库的名称。
     * </p>
     *
     * @param kbId         知识库ID
     * @param requestParam 包含新名称的请求体
     * @return 空结果
     */
    @PutMapping("/knowledge-base/{kb-id}")
    public Result<Void> renameKnowledgeBase(@PathVariable("kb-id") String kbId,
                                            @RequestBody KnowledgeBaseUpdateRequest requestParam) {
        StpUtil.checkRole("admin");
        knowledgeBaseService.rename(kbId, requestParam);
        return Results.success();
    }

    /**
     * 删除知识库
     *
     * <p>
     * 删除指定的知识库，同时删除关联的文档和向量数据。
     * </p>
     *
     * @param kbId 知识库ID
     * @return 空结果
     */
    @DeleteMapping("/knowledge-base/{kb-id}")
    public Result<Void> deleteKnowledgeBase(@PathVariable("kb-id") String kbId) {
        StpUtil.checkRole("admin");
        knowledgeBaseService.delete(kbId);
        return Results.success();
    }

    /**
     * 查询知识库详情
     *
     * <p>
     * 根据 ID 获取单个知识库的详细信息。
     * </p>
     *
     * @param kbId 知识库ID
     * @return 知识库详情（包含名称、创建时间、文档数量等）
     */
    @GetMapping("/knowledge-base/{kb-id}")
    public Result<KnowledgeBaseVO> queryKnowledgeBase(@PathVariable("kb-id") String kbId) {
        return Results.success(knowledgeBaseService.queryById(kbId));
    }

    /**
     * 分页查询知识库列表
     *
     * <p>
     * 返回当前用户的知识库列表，支持分页。
     * </p>
     *
     * @param requestParam 分页参数
     * @return 知识库分页结果
     */
    @GetMapping("/knowledge-base")
    public Result<IPage<KnowledgeBaseVO>> pageQuery(KnowledgeBasePageRequest requestParam) {
        return Results.success(knowledgeBaseService.pageQuery(requestParam));
    }
}
