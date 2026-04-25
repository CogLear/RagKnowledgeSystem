package com.rks.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP Server Spring Boot 应用启动类
 *
 * <p>MCP Server 是一个独立的 Spring Boot 应用，作为 Model Context Protocol (MCP) 服务器运行。
 * 它通过 HTTP + JSON-RPC 2.0 协议对外暴露工具（tools），供主应用（bootstrap）调用。
 *
 * <h2>启动流程</h2>
 * <ol>
 *   <li>Spring Boot 扫描并加载所有 {@link org.springframework.stereotype.Component} 注解的类</li>
 *   <li>{@link com.rks.mcp.core.DefaultMCPToolRegistry} 在 {@link jakarta.annotation.PostConstruct} 阶段
 *       自动扫描并注册所有 {@link com.rks.mcp.core.MCPToolExecutor} 实现类</li>
 *   <li>MCP Endpoint 开始接收 HTTP POST /mcp 请求</li>
 * </ol>
 *
 * <h2>核心端口</h2>
 * <ul>
 *   <li>MCP Server HTTP 端口：9099（见 application.yaml）</li>
 *   <li>MCP 协议端点：POST /mcp</li>
 * </ul>
 *
 * <h2>与主应用的关系</h2>
 * <p>MCP Server 与主应用（bootstrap，端口 9090）通过 HTTP 通信。，
 * 调用 initialize、tools/list、tools/call 三个核心方法。
 *
 * @see com.rks.mcp.core.MCPToolExecutor
 * @see com.rks.mcp.endpoint.MCPEndpoint
 */
@SpringBootApplication
public class MCPServerApplication {

    /**
     * 应用入口，启动 Spring Boot 容器
     *
     * @param args 命令行参数，传递给 SpringApplication.run()
     */
    public static void main(String[] args) {
        SpringApplication.run(MCPServerApplication.class, args);
    }
}