
package com.rks.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.rks.framework.convention.Result;
import com.rks.framework.web.Results;
import com.rks.rag.controller.request.SampleQuestionCreateRequest;
import com.rks.rag.controller.request.SampleQuestionPageRequest;
import com.rks.rag.controller.request.SampleQuestionUpdateRequest;
import com.rks.rag.controller.vo.SampleQuestionVO;
import com.rks.rag.service.SampleQuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 示例问题控制器（欢迎页展示）
 */
@RestController
@RequiredArgsConstructor
public class SampleQuestionController {

    private final SampleQuestionService sampleQuestionService;

    /**
     * 随机获取示例问题列表
     *
     * <p>
     * 返回随机挑选的示例问题，用于前端欢迎页展示。
     * 帮助用户快速了解系统能力。
     * </p>
     *
     * @return 随机示例问题列表
     */
    @GetMapping("/rag/sample-questions")
    public Result<List<SampleQuestionVO>> listSampleQuestions() {
        return Results.success(sampleQuestionService.listRandomQuestions());
    }

    /**
     * 分页查询示例问题列表
     *
     * <p>
     * 返回系统中所有示例问题，支持分页。
     * 用于管理后台的问题管理功能。
     * </p>
     *
     * @param requestParam 分页和过滤参数
     * @return 示例问题分页结果
     */
    @GetMapping("/sample-questions")
    public Result<IPage<SampleQuestionVO>> pageQuery(SampleQuestionPageRequest requestParam) {
        return Results.success(sampleQuestionService.pageQuery(requestParam));
    }

    /**
     * 查询示例问题详情
     *
     * @param id 示例问题ID
     * @return 示例问题详情
     */
    @GetMapping("/sample-questions/{id}")
    public Result<SampleQuestionVO> queryById(@PathVariable String id) {
        return Results.success(sampleQuestionService.queryById(id));
    }

    /**
     * 创建示例问题
     *
     * <p>
     * 添加新的示例问题到系统。
     * 示例问题用于意图匹配或欢迎页展示。
     * </p>
     *
     * @param requestParam 问题内容及配置
     * @return 新建问题的ID
     */
    @PostMapping("/sample-questions")
    public Result<String> create(@RequestBody SampleQuestionCreateRequest requestParam) {
        return Results.success(sampleQuestionService.create(requestParam));
    }

    /**
     * 更新示例问题
     *
     * @param id           问题ID
     * @param requestParam 新的问题内容
     * @return 空结果
     */
    @PutMapping("/sample-questions/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody SampleQuestionUpdateRequest requestParam) {
        sampleQuestionService.update(id, requestParam);
        return Results.success();
    }

    /**
     * 删除示例问题
     *
     * @param id 问题ID
     * @return 空结果
     */
    @DeleteMapping("/sample-questions/{id}")
    public Result<Void> delete(@PathVariable String id) {
        sampleQuestionService.delete(id);
        return Results.success();
    }
}
