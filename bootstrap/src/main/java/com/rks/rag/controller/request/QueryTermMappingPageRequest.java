
package com.rks.rag.controller.request;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 关键词映射分页查询请求
 *
 * <p>
 * 支持按关键字过滤匹配 sourceTerm 或 targetTerm。
 * </p>
 *
 * @see com.rks.rag.service.QueryTermMappingAdminService
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class QueryTermMappingPageRequest extends Page {

    /**
     * 关键词（支持匹配 sourceTerm/targetTerm）
     */
    private String keyword;
}
