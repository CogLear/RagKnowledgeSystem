
package com.rks.knowledge.controller.request;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库分页查询请求
 *
 * <p>
 * 支持按知识库名称模糊匹配过滤。
 * </p>
 *
 * @see com.rks.knowledge.service.KnowledgeBaseService
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class KnowledgeBasePageRequest extends Page {

    /**
     * 知识库名称（支持模糊匹配）
     */
    private String name;
}
