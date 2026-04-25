
package com.rks.rag.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 查询术语映射实体
 *
 * <p>
 * 存储用户 query 中的同义词/缩写到标准术语的映射规则，
 * 用于查询改写时将用户输入转换为标准查询词。
 * </p>
 *
 * @see com.rks.rag.service.QueryTermMappingAdminService
 */
@Data
@TableName("t_query_term_mapping")
public class QueryTermMappingDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 业务域/系统标识，如biz、group、data_security等，可选
     */
    private String domain;

    /**
     * 用户原始短语
     */
    private String sourceTerm;

    /**
     * 归一化后的目标短语
     */
    private String targetTerm;

    /**
     * 匹配类型 1：精确匹配 2：前缀匹配 3：正则匹配 4：整词匹配
     */
    private Integer matchType;

    /**
     * 优先级，数值越小优先级越高（一般长词在前）
     */
    private Integer priority;

    /**
     * 是否生效 1：生效 0：禁用
     */
    private Boolean enabled;

    /**
     * 备注
     */
    private String remark;

    private String createBy;

    private String updateBy;

    private Date createTime;

    private Date updateTime;
}
