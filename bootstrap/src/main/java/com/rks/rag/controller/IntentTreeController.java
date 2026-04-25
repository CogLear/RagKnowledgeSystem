package com.rks.rag.controller;
import com.rks.framework.convention.Result;
import com.rks.framework.web.Results;
import com.rks.rag.service.IntentTreeService;
import com.rks.rag.controller.request.IntentNodeBatchRequest;
import com.rks.rag.controller.request.IntentNodeCreateRequest;
import com.rks.rag.controller.request.IntentNodeUpdateRequest;
import com.rks.rag.controller.vo.IntentNodeTreeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
/**
 * 意图树控制器
 * 提供意图节点树的查询、创建、更新和删除功能
 */
@RestController
@RequiredArgsConstructor
public class IntentTreeController {

    private final IntentTreeService intentTreeService;

    /**
     * 获取完整的意图节点树
     *
     * <p>
     * 返回系统中所有意图节点，以树形结构组织。
     * 树形结构用于前端展示意图分类和层级关系。
     * </p>
     *
     * @return 意图节点树列表
     */
    @GetMapping("/intent-tree/trees")
    public Result<List<IntentNodeTreeVO>> tree() {
        return Results.success(intentTreeService.getFullTree());
    }

    /**
     * 创建意图节点
     *
     * <p>
     * 在意图树中创建一个新的节点。
     * 节点包含意图名称、匹配规则、关联知识库等配置。
     * </p>
     *
     * @param requestParam 节点配置信息
     * @return 新建节点的ID
     */
    @PostMapping("/intent-tree")
    public Result<String> createNode(@RequestBody IntentNodeCreateRequest requestParam) {
        return Results.success(intentTreeService.createNode(requestParam));
    }

    /**
     * 更新意图节点
     *
     * @param id           节点ID
     * @param requestParam 新的配置信息
     */
    @PutMapping("/intent-tree/{id}")
    public void updateNode(@PathVariable String id, @RequestBody IntentNodeUpdateRequest requestParam) {
        intentTreeService.updateNode(id, requestParam);
    }

    /**
     * 删除意图节点
     *
     * @param id 节点ID
     */
    @DeleteMapping("/intent-tree/{id}")
    public void deleteNode(@PathVariable String id) {
        intentTreeService.deleteNode(id);
    }

    /**
     * 批量启用意图节点
     *
     * <p>
     * 将多个节点设置为启用状态。
     * 启用的节点会在意图识别时被匹配。
     * </p>
     *
     * @param requestParam 包含节点ID列表的请求体
     */
    @PostMapping("/intent-tree/batch/enable")
    public void batchEnable(@RequestBody IntentNodeBatchRequest requestParam) {
        intentTreeService.batchEnableNodes(requestParam.getIds());
    }

    /**
     * 批量停用意图节点
     *
     * <p>
     * 将多个节点设置为停用状态。
     * 停用的节点不会被意图识别匹配。
     * </p>
     *
     * @param requestParam 包含节点ID列表的请求体
     */
    @PostMapping("/intent-tree/batch/disable")
    public void batchDisable(@RequestBody IntentNodeBatchRequest requestParam) {
        intentTreeService.batchDisableNodes(requestParam.getIds());
    }

    /**
     * 批量删除意图节点
     *
     * @param requestParam 包含节点ID列表的请求体
     */
    @PostMapping("/intent-tree/batch/delete")
    public void batchDelete(@RequestBody IntentNodeBatchRequest requestParam) {
        intentTreeService.batchDeleteNodes(requestParam.getIds());
    }
}
