
package com.rks.user.controller.request;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户分页查询请求
 *
 * <p>
 * 支持按用户名或角色关键字过滤。
 * </p>
 *
 * @see com.rks.user.service.UserService
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class UserPageRequest extends Page {

    /**
     * 关键词（支持匹配用户名/角色）
     */
    private String keyword;
}
