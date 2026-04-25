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
import java.util.*;
import java.util.stream.Collectors;

/**
 * MySQL 数据查询 MCP 工具执行器
 *
 * <p>实现 {@link MCPToolExecutor} 接口，专门用于执行只读数据查询。
 *
 * <h2>安全特性</h2>
 * <ul>
 *   <li>仅允许预定义模板查询（SELECT、COUNT）</li>
 *   <li>仅能查询白名单中的表</li>
 *   <li>敏感列（password/token/secret 等）会被脱敏</li>
 *   <li>每个表有独立的行数限制</li>
 *   <li>不允许 WHERE、JOIN 等复杂查询</li>
 * </ul>
 *
 * <h2>工具 ID</h2>
 * <p>toolId = {@code mysql_query}
 *
 * <h2>使用前提</h2>
 * <p>LLM 应先调用 {@code mysql_schema.get_table_schema} 了解表结构，
 * 再调用本工具进行数据查询。
 *
 * @see MCPToolExecutor
 */
@Slf4j
@Component
public class MySQLQueryExecutor implements MCPToolExecutor {

    /** 工具 ID */
    private static final String TOOL_ID = "mysql_query";

    /** MySQL 连接 */
    private volatile Connection connection;

    private final MySQLMCPProperties properties;

    @Autowired
    public MySQLQueryExecutor(MySQLMCPProperties properties) {
        this.properties = properties;
    }

    @Override
    public MCPToolDefinition getToolDefinition() {
        Map<String, MCPToolDefinition.ParameterDef> parameters = new LinkedHashMap<>();

        // tableName 参数
        parameters.put("tableName", MCPToolDefinition.ParameterDef.builder()
                .description("表名，从允许的表中选择")
                .type("string")
                .required(true)
                .enumValues(java.util.Arrays.asList(properties.getAllowedTableNames()))
                .build());

        // operation 参数
        parameters.put("operation", MCPToolDefinition.ParameterDef.builder()
                .description("操作类型：SELECT(查询数据)、COUNT(统计行数)")
                .type("string")
                .required(true)
                .enumValues(List.of("SELECT", "COUNT"))
                .build());

        // columns 参数（可选）
        parameters.put("columns", MCPToolDefinition.ParameterDef.builder()
                .description("要查询的列，逗号分隔，默认为 *（所有列）")
                .type("string")
                .required(false)
                .defaultValue("*")
                .build());

        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("【强制】使用本工具前必须先调用 mysql_schema.get_table_schema(tableName=表名) 获取表结构。"
                        + "表名必须使用 mysql_schema 返回的表名（如 users, orders），不能使用中文表名。"
                        + "确认列名后再调用本工具执行 SELECT 或 COUNT。敏感列会被脱敏显示为 ******** 。")
                .parameters(parameters)
                .requireUserId(false)
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
        try {
            String tableName = request.getStringParameter("tableName");
            String operation = request.getStringParameter("operation");
            String columns = request.getStringParameter("columns");

            // 参数校验
            if (tableName == null || tableName.isBlank()) {
                return MCPToolResponse.error(TOOL_ID, "INVALID_PARAMS", "表名不能为空");
            }
            if (operation == null || operation.isBlank()) {
                return MCPToolResponse.error(TOOL_ID, "INVALID_PARAMS", "操作类型不能为空");
            }
            if (columns == null || columns.isBlank()) {
                columns = "*";
            }

            // 校验表访问权限
            MySQLMCPProperties.TableConfig config = properties.findTableConfig(tableName);
            if (config == null) {
                return MCPToolResponse.error(TOOL_ID, "ACCESS_DENIED",
                        "表 [" + tableName + "] 不在允许列表中");
            }

            // 检查是否允许 SELECT（maxRows > 0 表示允许）
            if ("SELECT".equalsIgnoreCase(operation) && config.getMaxRows() <= 0) {
                return MCPToolResponse.error(TOOL_ID, "ACCESS_DENIED",
                        "表 [" + tableName + "] 不允许 SELECT 操作");
            }

            // 执行操作
            String result = switch (operation.toUpperCase()) {
                case "SELECT" -> executeSelect(tableName, columns, config.getMaxRows());
                case "COUNT" -> executeCount(tableName);
                default -> throw new IllegalArgumentException("不支持的操作: " + operation);
            };

            return MCPToolResponse.success(TOOL_ID, result);

        } catch (IllegalArgumentException e) {
            return MCPToolResponse.error(TOOL_ID, "INVALID_PARAMS", e.getMessage());
        } catch (Exception e) {
            log.error("Query 执行失败, tableName={}", request.getStringParameter("tableName"), e);
            return MCPToolResponse.error(TOOL_ID, "EXECUTION_ERROR", "查询失败: " + e.getMessage());
        }
    }

    // ==================== 查询实现 ====================

    /**
     * 执行 SELECT 查询
     */
    private String executeSelect(String tableName, String columns, int maxRows) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("【查询结果: ").append(tableName).append("】\n\n");

        // 获取列信息（用于脱敏）
        Set<String> sensitiveColumns = getSensitiveColumns(tableName);
        List<String> columnList = parseColumns(columns);
        List<String> allColumns = getAllColumns(tableName);

        // 验证请求的列是否都存在
        if (!"*".equals(columns)) {
            for (String col : columnList) {
                if (!allColumns.contains(col)) {
                    throw new IllegalArgumentException("列 [" + col + "] 不存在于表 [" + tableName + "] 中");
                }
            }
        } else {
            columnList = allColumns;
        }

        // 输出字段列表
        sb.append("字段: ").append(String.join(", ", columnList)).append("\n\n");

        // 执行 SELECT（使用预定义模板，禁止自由 SQL）
        String selectSql = buildSelectSql(tableName, columnList);

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(selectSql)) {

            pstmt.setInt(1, maxRows);

            try (ResultSet rs = pstmt.executeQuery()) {
                int rowCount = 0;
                while (rs.next()) {
                    rowCount++;
                    sb.append("第 ").append(rowCount).append(" 行:\n");
                    for (String colName : columnList) {
                        String value = sensitiveColumns.contains(colName)
                                ? "********"
                                : safeGet(rs, colName);
                        sb.append("  ").append(colName).append(": ").append(value).append("\n");
                    }
                    sb.append("\n");
                }

                if (rowCount == 0) {
                    sb.append("无数据\n");
                }

                sb.append("共 ").append(rowCount).append(" 行（限制: ").append(maxRows).append("）");
            }
        }

        return sb.toString();
    }

    /**
     * 执行 COUNT 查询
     */
    private String executeCount(String tableName) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("【行数统计: ").append(tableName).append("】\n\n");

        String countSql = "SELECT COUNT(*) AS total FROM `" + tableName + "`";

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(countSql)) {

            if (rs.next()) {
                sb.append("总行数: ").append(rs.getInt("total"));
            }
        }

        return sb.toString();
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建安全的 SELECT SQL
     */
    private String buildSelectSql(String tableName, List<String> columns) {
        String columnClause = columns.stream()
                .map(col -> "`" + col + "`")
                .collect(Collectors.joining(", "));

        return String.format("SELECT %s FROM `%s` LIMIT ?",
                columnClause, tableName);
    }

    /**
     * 获取指定表的所有列名
     */
    private Set<String> getSensitiveColumns(String tableName) throws SQLException {
        Set<String> sensitive = new java.util.HashSet<>();

        String sql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, properties.getDatabase());
            pstmt.setString(2, tableName);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String colName = rs.getString("COLUMN_NAME");
                    if (isSensitiveColumn(colName)) {
                        sensitive.add(colName);
                    }
                }
            }
        }

        return sensitive;
    }

    /**
     * 获取指定表的所有列名
     */
    private List<String> getAllColumns(String tableName) throws SQLException {
        List<String> columns = new ArrayList<>();

        String sql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, properties.getDatabase());
            pstmt.setString(2, tableName);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME"));
                }
            }
        }

        return columns;
    }

    /**
     * 解析列名字符串
     */
    private List<String> parseColumns(String columns) {
        return Arrays.stream(columns.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
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