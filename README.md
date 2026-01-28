# Anthropic Proxy Java

Anthropic API 代理服务，将 Anthropic API 请求转换为 OpenAI 格式，支持指标监控和可视化 Dashboard。

## 功能特性

- **API 代理**: 将 Anthropic Messages API 请求转换为 OpenAI Chat Completions 格式
- **流式支持**: 完整的流式响应支持 (Server-Sent Events)
- **工具调用**: 支持 Anthropic 工具调用 (Tools/Function Calling)
- **用户识别**: 多方式用户识别 (API Key、Header、IP)
- **指标监控**: 集成 Prometheus 指标和 Dashboard
- **响应式**: 基于 Spring WebFlux 的非阻塞架构

## 快速开始

### 环境要求

- Java 17+
- Maven 3.6+

### 构建

```bash
mvn clean package -DskipTests
```

### 运行

```bash
# 设置 API Key
export OPENAI_API_KEY=your_api_key

# 启动服务
java -jar target/anthropic-proxy-1.0.0-SNAPSHOT.jar
```

服务将在 `http://localhost:8080` 启动。

## 配置

在 `src/main/resources/application.yml` 中配置:

```yaml
server:
  port: 8080

proxy:
  openai:
    base-url: https://api.anthropic.com/v1  # OpenAI 兼容端点
    api-key: ${OPENAI_API_KEY:}             # API Key
    timeout: 300000                          # 超时时间 (毫秒)

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
```

## API 使用

### 发送消息

```bash
curl -X POST http://localhost:8080/anthropic/v1/messages \
  -H "Content-Type: application/json" \
  -H "x-api-key: your_api_key" \
  -d '{
    "model": "claude-sonnet-4-20250514",
    "max_tokens": 1024,
    "messages": [
      {"role": "user", "content": "Hello, Claude!"}
    ]
  }'
```

### 流式请求

```bash
curl -X POST http://localhost:8080/anthropic/v1/messages \
  -H "Content-Type: application/json" \
  -H "x-api-key: your_api_key" \
  -H "anthropic-beta: prompt-caching-1" \
  -d '{
    "model": "claude-sonnet-4-20250514",
    "max_tokens": 1024,
    "stream": true,
    "messages": [
      {"role": "user", "content": "Tell me a story about AI."}
    ]
  }'
```

### 健康检查

```bash
curl http://localhost:8080/anthropic/health
```

## 监控指标

### Prometheus 端点

```bash
curl http://localhost:8080/actuator/prometheus
```

### Dashboard

访问 `http://localhost:8080/dashboard` 查看可视化监控面板。

## 项目结构

```
src/main/java/com/phodal/anthropicproxy/
├── AnthropicProxyApplication.java    # 应用入口
├── config/
│   ├── JacksonConfig.java            # JSON 配置
│   └── WebConfig.java                # Web 配置
├── controller/
│   ├── AnthropicProxyController.java # API 控制器
│   └── MetricsDashboardController.java # Dashboard 控制器
├── model/
│   ├── anthropic/                    # Anthropic API 模型
│   └── openai/                       # OpenAI API 模型
└── service/
    ├── MetricsService.java           # 指标服务
    ├── OpenAISdkService.java         # OpenAI SDK 服务
    └── UserIdentificationService.java # 用户识别服务
```

## 技术栈

- Spring Boot 3.3.7
- Spring WebFlux
- OpenAI Java SDK 4.16.1
- Micrometer Prometheus
- Thymeleaf
- Lombok

## License

MIT