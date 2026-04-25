package com.rks.mcp.executor;

import com.rks.mcp.config.MySQLMCPProperties;
import com.rks.mcp.core.MCPToolDefinition;
import com.rks.mcp.core.MCPToolExecutor;
import com.rks.mcp.core.MCPToolRequest;
import com.rks.mcp.core.MCPToolResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MySQL Schema 查询 MCP 工具执行器
 *
 * <p>实现 {@link MCPToolExecutor} 接口，专门用于查询数据库表结构元数据。
 * 提供两个工具方法：
 * <ul>
 *   <li>list_tables - 列出所有允许访问的表及其描述</li>
 *   <li>get_table_schema - 获取指定表的详细结构（列、索引、注释）</li>
 * </ul>
 *
 * <h2>安全特性</h2>
 * <ul>
 *   <li>仅读取 INFORMATION_SCHEMA，不涉及实际数据</li>
 *   <li>仅返回已配置的表结构</li>
 *   <li>敏感列会在描述中标记</li>
 * </ul>
 *
 * <h2>工具 ID</h2>
 * <p>toolId = {@code mysql_schema}
 *
 * @see MCPToolExecutor
 */
@Slf4j
@Component
public class MySQLSchemaExecutor implements MCPToolExecutor {

    /** 工具 ID */
    private static final String TOOL_ID = "mysql_schema";

    /** 敏感列名关键词（大小写不敏感匹配，从配置读取） */
    private Set<String> sensitivePatterns;

    /** MySQL 连接 */
    private volatile Connection connection;

    private final MySQLMCPProperties properties;

    @Autowired
    public MySQLSchemaExecutor(MySQLMCPProperties properties) {
        this.properties = properties;
    }

    @Override
    public MCPToolDefinition getToolDefinition() {
        Map<String, MCPToolDefinition.ParameterDef> parameters = new LinkedHashMap<>();

        // tableName 参数（可选）
        parameters.put("tableName", MCPToolDefinition.ParameterDef.builder()
                .description("表名，不传则列出所有允许的表")
                .type("string")
                .required(false)
                .enumValues(java.util.Arrays.asList(properties.getAllowedTableNames()))
                .build());

        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("MySQL 表结构查询工具，用于查看数据库表列表和详细结构。"
                        + "不传 tableName 时返回所有允许查询的表列表，传 tableName 时返回该表的详细结构（列信息、索引、注释）。")
                .parameters(parameters)
                .requireUserId(false)
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
        try {
            String tableName = request.getStringParameter("tableName");

            // 根据是否有 tableName 参数决定执行哪个方法
            String result;
            if (tableName == null || tableName.isBlank()) {
                result = listTables();
            } else {
                // 校验表是否在白名单中
                if (!isTableAllowed(tableName)) {
                    return MCPToolResponse.error(TOOL_ID, "ACCESS_DENIED",
                            "表 [" + tableName + "] 不在允许列表中");
                }
                result = getTableSchema(tableName);
            }

            return MCPToolResponse.success(TOOL_ID, result);

        } catch (Exception e) {
            log.error("Schema 查询失败", e);
            return MCPToolResponse.error(TOOL_ID, "EXECUTION_ERROR", "查询失败: " + e.getMessage());
        }
    }

    // ==================== 工具方法实现 ====================

    /**
     * 列出所有允许访问的表
     */
    private String listTables() {
        StringBuilder sb = new StringBuilder();
        sb.append("【可查询的表列表】\n\n");

        List<MySQLMCPProperties.TableConfig> tables = properties.getAllowedTables();
        if (tables == null || tables.isEmpty()) {
            sb.append("未配置允许查询的表");
            return sb.toString();
        }

        sb.append(String.format("%-20s %s\n", "表名", "描述"));
        sb.append("-".repeat(60)).append("\n");

        for (MySQLMCPProperties.TableConfig table : tables) {
            sb.append(String.format("%-20s %s\n",
                    table.getTableName(),
                    table.getDescription() != null ? table.getDescription() : ""));
        }

        sb.append("\n使用 get_table_schema(tableName) 查看具体表结构");

        return sb.toString();
    }

    /**
     * 获取指定表的详细结构
     */
    private String getTableSchema(String tableName) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("【表结构: ").append(tableName).append("】\n\n");

        // 获取配置的描述
        MySQLMCPProperties.TableConfig config = properties.findTableConfig(tableName);
        if (config != null && config.getDescription() != null && !config.getDescription().isBlank()) {
            sb.append("描述: ").append(config.getDescription()).append("\n\n");
        }

        // 查询列信息
        sb.append("## 列信息\n");
        sb.append(String.format("%-20s %-18s %-8s %-10s %s\n",
                "列名", "类型", "可空", "默认值", "注释"));
        sb.append("-".repeat(80)).append("\n");

        String columnSql = """
            SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            ORDER BY ORDINAL_POSITION
            """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(columnSql)) {

            pstmt.setString(1, properties.getDatabase());
            pstmt.setString(2, tableName);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String colName = safeGet(rs, "COLUMN_NAME");
                    String colType = safeGet(rs, "COLUMN_TYPE");
                    String nullable = safeGet(rs, "IS_NULLABLE");
                    String defaultVal = safeGet(rs, "COLUMN_DEFAULT");
                    String comment = safeGet(rs, "COLUMN_COMMENT");

                    // 敏感列标记
                    String marker = isSensitiveColumn(colName) ? "*" : " ";
                    sb.append(marker);
                    sb.append(String.format("%-20s %-18s %-8s %-10s %s\n",
                            colName, colType, nullable,
                            defaultVal != null ? defaultVal : "NULL",
                            comment != null ? comment : ""));
                }
            }
        }

        // 查询索引信息
        sb.append("\n## 索引信息\n");
        String indexSql = """
            SELECT INDEX_NAME, NON_UNIQUE,
                   GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS COLUMNS
            FROM INFORMATION_SCHEMA.STATISTICS
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            GROUP BY INDEX_NAME, NON_UNIQUE
            ORDER BY INDEX_NAME
            """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(indexSql)) {

            pstmt.setString(1, properties.getDatabase());
            pstmt.setString(2, tableName);

            try (ResultSet rs = pstmt.executeQuery()) {
                boolean hasIndexes = false;
                while (rs.next()) {
                    hasIndexes = true;
                    String indexName = safeGet(rs, "INDEX_NAME");
                    String nonUnique = rs.getString("NON_UNIQUE");
                    String columns = safeGet(rs, "COLUMNS");
                    String indexType = "0".equals(nonUnique) ? "UNIQUE" : "INDEX";

                    sb.append(String.format("- %s (%s): %s\n", indexName, indexType, columns));
                }
                if (!hasIndexes) {
                    sb.append("无\n");
                }
            }
        }

        sb.append("\n*标记的列包含敏感信息，查询结果会被脱敏\n");

        return sb.toString();
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查表是否在白名单中
     */
    private boolean isTableAllowed(String tableName) {
        return properties.findTableConfig(tableName) != null;
    }

    /**
     * 判断列名是否为敏感列
     */
    private boolean isSensitiveColumn(String columnName) {
        if (columnName == null) {
            return false;
        }
        Set<String> patterns = properties.getSensitivePatterns() != null
                ? new java.util.HashSet<>(properties.getSensitivePatterns())
                : java.util.Collections.emptySet();
        if (patterns.isEmpty()) {
            return false;
        }
        String lower = columnName.toLowerCase();
        return patterns.stream().anyMatch(p -> lower.contains(p.toLowerCase()));
    }

    /**
     * 获取数据库连接（懒加载）
     */
    private Connection getConnection() throws SQLException {
        Connection conn = this.connection;
        if (conn != null && conn.isValid(1)) {
            return conn;
        }

        String url = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                properties.getHost(), properties.getPort(), properties.getDatabase());

        conn = DriverManager.getConnection(url, properties.getUsername(), properties.getPassword());
        this.connection = conn;

        return conn;
    }

    /**
     * 安全获取 ResultSet 中的字符串值
     */
    private String safeGet(ResultSet rs, String columnName) throws SQLException {
        String value = rs.getString(columnName);
        return value != null ? value : "";
    }
}