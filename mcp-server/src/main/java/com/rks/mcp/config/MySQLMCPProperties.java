package com.rks.mcp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL MCP 配置属性
 *
 * <p>配置 MySQL 连接信息和允许访问的表。
 * 在 application.yml 中通过 mcp.mysql 前缀配置。
 *
 * <h2>配置示例</h2>
 * <pre>{@code
 * mcp:
 *   mysql:
 *     host: localhost
 *     port: 3306
 *     database: myapp
 *     username: readonly_user
 *     password: secret
 *     allowedTables:
 *       - tableName: users
 *         description: 用户信息表
 *         maxRows: 50
 *       - tableName: orders
 *         description: 订单表
 *         maxRows: 100
 * }</pre>
 *
 * <h2>使用说明</h2>
 * <ul>
 *   <li>mysql_schema 工具：读取所有配置的表结构（description 字段供 LLM 理解表用途）</li>
 *   <li>mysql_query 工具：仅能查询配置中 maxRows > 0 的表，SELECT 结果受 maxRows 限制</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.mysql")
@Validated
public class MySQLMCPProperties {

    /** MySQL 连接 host */
    @NotBlank
    private String host = "localhost";

    /** MySQL 连接端口 */
    private int port = 3306;

    /** 数据库名称 */
    @NotBlank
    private String database;

    /** 用户名 */
    @NotBlank
    private String username;

    /** 密码 */
    @NotBlank
    private String password;

    /** 敏感列名关键词（大小写不敏感匹配），包含这些词的列名会被脱敏 */
    private List<String> sensitivePatterns = new ArrayList<>();

    /** 允许查询的表配置列表 */
    private List<TableConfig> allowedTables = new ArrayList<>();

    /**
     * 表配置
     */
    @Data
    public static class TableConfig {
        /** 表名 */
        @NotBlank
        private String tableName;

        /** 表描述（供 LLM 理解表用途） */
        private String description;

        /** SELECT 查询最大返回行数，设为 0 则不允许 SELECT */
        @Min(0)
        private int maxRows = 100;
    }

    /**
     * 根据表名查找表配置（大小写不敏感）
     *
     * @param tableName 表名
     * @return 表配置，未找到返回 null
     */
    public TableConfig findTableConfig(String tableName) {
        if (tableName == null) {
            return null;
        }
        return allowedTables.stream()
                .filter(t -> t.getTableName().equalsIgnoreCase(tableName))
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取所有允许的表名列表
     *
     * @return 表名数组
     */
    public String[] getAllowedTableNames() {
        return allowedTables.stream()
                .map(TableConfig::getTableName)
                .toArray(String[]::new);
    }
}