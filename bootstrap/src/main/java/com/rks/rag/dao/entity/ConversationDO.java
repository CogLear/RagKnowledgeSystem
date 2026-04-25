
package com.rks.rag.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 会话实体
 *
 * <p>
 * 存储用户的对话会话信息，包括会话ID、标题、最后活跃时间等。
 * </p>
 *
 * @see com.rks.rag.dao.entity.ConversationMessageDO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_conversation")
public class ConversationDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String conversationId;

    private String userId;

    private String title;

    private Date lastTime;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
