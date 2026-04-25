
package com.rks.knowledge.controller.request;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识分块分页查询请求
 *
 * <p>
 * 支持按启用状态过滤。
 * </p>
 *
 * @see com.rks.knowledge.service.KnowledgeChunkService
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class KnowledgeChunkPageRequest extends Page {

    private Integer enabled;
}
