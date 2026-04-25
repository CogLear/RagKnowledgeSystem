# MCP 服务器 API 文档

## 概述

MCP (Model Context Protocol) 服务器基于 JSON-RPC 2.0 协议，通过 HTTP POST 方式提供工具调用能力。

- **基础 URL**: `http://localhost:9099/mcp`
- **协议**: JSON-RPC 2.0
- **Content-Type**: `application/json`

---

## API 方法

### 1. initialize

初始化协议连接，返回服务器信息。

**请求示例：**
```json
{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {}
}
```

**响应示例：**
```json
{
    "jsonrpc": "2.0",
    "id": 1,
    "result": {
        "protocolVersion": "2026-02-28",
        "capabilities": {
            "tools": {
                "listChanged": false
            }
        },
        "serverInfo": {
            "name": "ragent-mcp-server",
            "version": "0.0.1"
        }
    }
}
```

---

### 2. tools/list

获取所有可用工具的列表。

**请求示例：**
```json
{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/list",
    "params": {}
}
```

**响应示例：**
```json
{
    "jsonrpc": "2.0",
    "id": 2,
    "result": {
        "tools": [
            {
                "name": "sales_query",
                "description": "查询销售数据，支持按区域、产品、时间周期筛选",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "region": {
                            "type": "string",
                            "description": "销售区域"
                        },
                        "period": {
                            "type": "string",
                            "description": "时间周期"
                        }
                    },
                    "required": ["region"]
                }
            },
            {
                "name": "ticket_query",
                "description": "查询工单数据，支持按状态、优先级筛选",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "status": {
                            "type": "string",
                            "description": "工单状态"
                        }
                    }
                }
            },
            {
                "name": "weather_query",
                "description": "查询城市天气信息，支持查看当前实时天气和未来多天天气预报",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "city": {
                            "type": "string",
                            "description": "城市名称"
                        },
                        "queryType": {
                            "type": "string",
                            "enum": ["current", "forecast"],
                            "description": "查询类型"
                        },
                        "days": {
                            "type": "integer",
                            "description": "预报天数"
                        }
                    },
                    "required": ["city"]
                }
            }
        ]
    }
}
```

---

### 3. tools/call

调用指定工具。

**请求参数：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | string | 是 | 工具名称 |
| arguments | object | 否 | 工具参数 |

**工具名称：**

| 工具 | 说明 |
|------|------|
| `sales_query` | 销售数据查询 |
| `ticket_query` | 工单数据查询 |
| `weather_query` | 天气数据查询 |

---

#### 3.1 weather_query

天气查询工具。

**参数：**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| city | string | 是 | - | 城市名称 |
| queryType | string | 否 | current | 查询类型：current/forecast |
| days | integer | 否 | 3 | 预报天数（1-7） |

**支持的城市：**
北京、上海、广州、深圳、杭州、成都、武汉、南京、西安、重庆、长沙、天津、苏州、郑州、青岛、大连、厦门、昆明、哈尔滨、三亚

**请求示例（当前天气）：**
```json
{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
        "name": "weather_query",
        "arguments": {
            "city": "北京",
            "queryType": "current"
        }
    }
}
```

**响应示例：**
```json
{
    "jsonrpc": "2.0",
    "id": 3,
    "result": {
        "content": [
            {
                "type": "text",
                "text": "【北京 今日天气】\n\n日期: 2026年04月19日\n天气: 多云\n当前温度: 18°C\n最高温度: 22°C\n最低温度: 14°C\n相对湿度: 65%\n风向: 东南风\n风力: 3-4级\n空气质量: 良\n\n提示: 今日天气良好，适宜出行。"
            }
        ],
        "isError": false
    }
}
```

**请求示例（天气预报）：**
```json
{
    "jsonrpc": "2.0",
    "id": 4,
    "method": "tools/call",
    "params": {
        "name": "weather_query",
        "arguments": {
            "city": "上海",
            "queryType": "forecast",
            "days": 3
        }
    }
}
```

**响应示例：**
```json
{
    "jsonrpc": "2.0",
    "id": 4,
    "result": {
        "content": [
            {
                "type": "text",
                "text": "【上海 未来3天天气预报】\n\n📅 今天（04-19）\n   天气: 多云 | 温度: 16°C ~ 24°C\n   湿度: 60% | 东南风 2-3级\n\n📅 明天（04-20）\n   天气: 小雨 | 温度: 17°C ~ 22°C\n   湿度: 78% | 东风 3-4级\n\n📅 后天（04-21）\n   天气: 晴 | 温度: 15°C ~ 26°C\n   湿度: 45% | 东南风 2-3级\n\n趋势: 未来3天气温逐渐升高，注意防暑。"
            }
        ],
        "isError": false
    }
}
```

---

#### 3.2 sales_query

销售数据查询工具。

**参数：**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| region | string | 是 | - | 销售区域：华东、华南、华北、华中、西南、西北、东北 |
| period | string | 否 | 本月 | 时间周期：本月、本季度、本年 |
| queryType | string | 否 | summary | 查询类型：summary/details |

**请求示例：**
```json
{
    "jsonrpc": "2.0",
    "id": 5,
    "method": "tools/call",
    "params": {
        "name": "sales_query",
        "arguments": {
            "region": "华东",
            "period": "本月",
            "queryType": "summary"
        }
    }
}
```

**响应示例：**
```json
{
    "jsonrpc": "2.0",
    "id": 5,
    "result": {
        "content": [
            {
                "type": "text",
                "text": "【华东区域 本月销售数据汇总】\n\n总销售额: ¥1,234.56 万元\n同比增长: +15.8%\n完成率: 78.5%\n\n产品类别销售TOP3：\n1. 电子产品 - ¥456.78 万 (37%)\n2. 服装服饰 - ¥321.45 万 (26%)\n3. 食品饮料 - ¥234.56 万 (19%)"
            }
        ],
        "isError": false
    }
}
```

---

#### 3.3 ticket_query

工单数据查询工具。

**参数：**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| status | string | 否 | all | 工单状态：open/in_progress/resolved/closed/all |
| priority | string | 否 | all | 优先级：low/medium/high/critical/all |
| days | integer | 否 | 30 | 查询最近N天的数据 |

**请求示例：**
```json
{
    "jsonrpc": "2.0",
    "id": 6,
    "method": "tools/call",
    "params": {
        "name": "ticket_query",
        "arguments": {
            "status": "open",
            "priority": "high",
            "days": 7
        }
    }
}
```

**响应示例：**
```json
{
    "jsonrpc": "2.0",
    "id": 6,
    "result": {
        "content": [
            {
                "type": "text",
                "text": "【工单数据统计（最近7天）】\n\n工单总数: 156\n待处理(open): 45\n处理中(in_progress): 32\n已解决(resolved): 67\n已关闭(closed): 12\n\n按优先级分布：\n- 紧急(critical): 8\n- 高(high): 23\n- 中(medium): 67\n- 低(low): 58"
            }
        ],
        "isError": false
    }
}
```

---

## 错误响应

**错误格式：**
```json
{
    "jsonrpc": "2.0",
    "id": 7,
    "error": {
        "code": -32601,
        "message": "Tool not found: xxx"
    }
}
```

**错误码：**

| 错误码 | 说明 |
|--------|------|
| -32601 | Method not found - 方法不存在 |
| -32602 | Invalid params - 参数无效 |
| -32603 | Internal error - 服务器内部错误 |

**常见错误：**

| 场景 | 错误信息 |
|------|----------|
| 工具不存在 | `Tool not found: xxx` |
| 城市不支持 | `暂不支持查询该城市，当前支持：北京、上海、广州...` |
| 参数缺失 | `请提供城市名称` |
| 未知方法 | `Unknown method: xxx` |

---

## 调用示例

### cURL

```bash
# 工具列表
curl -X POST http://localhost:9099/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'

# 天气查询
curl -X POST http://localhost:9099/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"weather_query","arguments":{"city":"北京","queryType":"current"}}}'

# 销售查询
curl -X POST http://localhost:9099/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"sales_query","arguments":{"region":"华东","period":"本月"}}}'
```

### Java

```java
HttpClient client = HttpClient.newHttpClient();

HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("http://localhost:9099/mcp"))
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString("""
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "tools/call",
            "params": {
                "name": "weather_query",
                "arguments": {"city": "北京"}
            }
        }
        """))
    .build();

HttpResponse<String> response = client.send(request,
    HttpResponse.BodyHandlers.ofString());

System.out.println(response.body());
```

---

## 通知请求

JSON-RPC 通知请求（无 `id` 字段）不需要服务器响应，服务器返回 HTTP 204 No Content。

**示例：**
```json
{
    "jsonrpc": "2.0",
    "method": "notifications/initialized",
    "params": {}
}
```

**cURL：**
```bash
curl -X POST http://localhost:9099/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}'
```
