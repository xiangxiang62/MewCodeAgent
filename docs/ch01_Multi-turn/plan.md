# MewCode 第一阶段纯对话 Plan

## 架构概览

MewCode 第一阶段采用分层结构：入口层负责启动参数和配置路径解析，配置层负责读取 YAML 并校验必填字段，TUI 层负责终端输入输出和会话循环，LLM 层负责统一 Provider 抽象，具体供应商 Provider 负责请求构造、认证、SSE 解析和增量文本提取。

终端交互层只依赖统一 Provider 接口，不直接了解 OpenAI 或 Anthropic 的 HTTP 请求格式。这样后续新增供应商时，只需要实现新的 Provider 并接入工厂选择逻辑，不影响 TUI 和会话流程。

当前会话历史保存在进程内存中。每次用户输入后，将用户消息加入历史，再把完整历史交给 Provider。Provider 返回流式文本时，TUI 立即打印增量；流结束后，将完整助手回复加入历史。

## 核心数据结构

### AppConfig

应用级配置对象。

字段：
- `llm`: LLM 配置对象，包含供应商协议、模型、地址、密钥和可选扩展参数。

### LlmConfig

LLM 供应商配置对象。

字段：
- `protocol`: 协议标识，决定使用 OpenAI Provider 还是 Anthropic Provider。
- `model`: 模型名称。
- `baseUrl`: API 基础地址，对应 YAML 字段 `base_url`。
- `apiKey`: API 密钥，对应 YAML 字段 `api_key`。
- `maxTokens`: 可选输出 token 上限，主要用于 Anthropic Messages 请求。
- `thinking`: 可选 Claude extended thinking 配置。

### ThinkingConfig

Claude extended thinking 配置对象。

字段：
- `enabled`: 是否启用 extended thinking。
- `budgetTokens`: thinking token 预算，对应 YAML 字段 `budget_tokens`。
- `display`: thinking 展示策略，用于控制是否请求展示 thinking 内容。

### ChatMessage

一条对话消息。

字段：
- `role`: 消息角色，当前包括用户和助手。
- `content`: 消息文本内容。

### Role

消息角色枚举。

取值：
- `USER`: 用户消息，对外协议值为 `user`。
- `ASSISTANT`: 助手消息，对外协议值为 `assistant`。

### LlmProvider

统一 LLM Provider 接口。

方法：
- `name()`: 返回 Provider 名称，用于终端展示和调试。
- `streamChat(messages, callback)`: 接收完整对话历史，向模型发起流式聊天请求，通过回调输出增量文本，并在结束时返回完整助手回复。

### StreamCallback

流式文本回调接口。

方法：
- `onText(text)`: 当供应商返回新的文本增量时触发。

## 模块设计

### 入口模块

职责：
- 接收命令行参数。
- 解析配置文件路径。
- 加载配置。
- 根据配置创建 Provider。
- 初始化会话历史并启动 TUI。

配置查找顺序：
1. 命令行第一个参数。
2. 环境变量 `MEWCODE_CONFIG`。
3. 当前工作目录下的 `config.yaml` 或 `config.yml`。
4. 用户目录下的 `.mewcode/config.yaml` 或 `.mewcode/config.yml`。

### 配置模块

职责：
- 从 YAML 文件读取配置。
- 支持 `${ENV_NAME}` 环境变量占位符展开。
- 校验 `protocol`、`model`、`base_url`、`api_key` 四个核心字段。
- 对可选字段提供默认值。

对外接口：
- 根据路径加载并返回应用配置。
- 配置缺失或字段缺失时抛出包含明确字段名的错误。

### TUI 模块

职责：
- 建立终端交互式输入输出。
- 显示启动信息和当前 Provider。
- 接收用户输入。
- 支持退出命令。
- 支持清空当前会话历史。
- 将模型流式增量直接打印到终端。

命令：
- `/exit`: 退出程序。
- `/quit`: 退出程序。
- `/clear`: 清空当前会话历史。

### LLM 抽象模块

职责：
- 定义 Provider 统一接口。
- 定义消息、角色、流式回调等公共类型。
- 根据配置选择具体 Provider。

扩展方式：
- 新增协议时实现统一 Provider 接口。
- 在 Provider 工厂中增加协议分发。
- 不修改 TUI 会话循环。

### SSE 模块

职责：
- 从响应输入流中逐行读取 SSE。
- 聚合同一个事件中的 `data:` 行。
- 在事件结束时将 data 内容交给 Provider 解析。

边界：
- SSE 模块只解析 SSE 帧格式。
- 供应商 JSON 结构由具体 Provider 解析。

### OpenAI Provider

职责：
- 根据对话历史构造 OpenAI Chat Completions 请求。
- 使用 Bearer Token 认证。
- 请求流式响应。
- 从 SSE JSON 增量中提取文本内容。
- 将增量文本传给统一回调。

请求要点：
- endpoint 为 `base_url + /chat/completions`，如果配置已包含完整 endpoint 则直接使用。
- 请求体包含 `model`、`stream`、`messages`。

### Anthropic Provider

职责：
- 根据对话历史构造 Anthropic Messages 请求。
- 使用 `x-api-key` 认证。
- 请求流式响应。
- 从 Anthropic SSE JSON 增量中提取文本内容。
- 在配置启用 thinking 时传递 extended thinking 参数。

请求要点：
- endpoint 为 `base_url + /messages`，如果配置已包含完整 endpoint 则直接使用。
- 请求体包含 `model`、`max_tokens`、`stream`、`messages`。
- thinking 启用时附加 `thinking` 对象。

## 模块交互

启动流程：

```text
main
  -> resolve config path
  -> load YAML config
  -> validate config
  -> create provider by protocol
  -> create empty message history
  -> start chat console
```

单轮对话流程：

```text
用户输入文本
  -> TUI 添加 user 消息到历史
  -> TUI 调用 provider.streamChat(history, callback)
  -> Provider 发起 HTTP SSE 请求
  -> SSE 模块逐个事件读取 data
  -> Provider 解析供应商 JSON 并提取文本增量
  -> TUI callback 立即打印文本增量
  -> Provider 返回完整助手回复
  -> TUI 添加 assistant 消息到历史
```

清空历史流程：

```text
用户输入 /clear
  -> TUI 清空内存消息列表
  -> 终端提示 Conversation cleared
```

## 文件组织

```text
MewcodeAgent/
├── pom.xml
├── README.md
├── config.yaml
├── spec.md
├── plan.md
└── src/main/java/dev/mewcode/agent/
    ├── MewCodeApp.java
    ├── config/
    │   ├── AppConfig.java
    │   ├── ConfigLoader.java
    │   ├── LlmConfig.java
    │   └── ThinkingConfig.java
    ├── llm/
    │   ├── ChatMessage.java
    │   ├── LlmProvider.java
    │   ├── LlmProviderFactory.java
    │   ├── Role.java
    │   ├── StreamCallback.java
    │   ├── anthropic/
    │   │   └── AnthropicProvider.java
    │   ├── http/
    │   │   └── SseClient.java
    │   └── openai/
    │       └── OpenAiProvider.java
    └── ui/
        └── ChatConsole.java
```

## 技术决策

| 决策点 | 选择 | 理由 |
| --- | --- | --- |
| 开发语言 | Java | 符合项目当前方向，便于构建稳定的 CLI 工具。 |
| 构建工具 | Maven | 项目简单，依赖少，打包成可运行 jar 成本低。 |
| 终端输入 | JLine | 比标准输入更适合交互式命令行，后续可扩展历史和快捷键。 |
| 配置格式 | YAML | 可读性好，适合嵌套供应商配置和后续扩展。 |
| HTTP 客户端 | JDK 标准 HTTP 能力 | 当前实现保持依赖轻量；如果统一要求 Java 17，可切换到 `java.net.http.HttpClient`。 |
| 流式协议 | SSE | OpenAI 和 Anthropic 都支持流式 SSE，满足逐步打印需求。 |
| 会话记忆 | 进程内 List | 第一阶段只要求当前会话多轮记忆，不做持久化。 |
| Provider 抽象 | 统一接口 + 工厂选择 | 保持 TUI 与供应商协议解耦，便于新增后端。 |
| Claude thinking | 配置开关 + 参数透传 | 满足当前需求，同时避免把 thinking 逻辑泄漏到 TUI。 |

## Spec 覆盖关系

- F1、F10 由 TUI 模块覆盖。
- F2、F4 由 TUI 会话循环和消息结构覆盖。
- F3、N1 由 SSE 模块和 Provider 流式回调覆盖。
- F5 由 OpenAI Provider 覆盖。
- F6、F7 由 Anthropic Provider 覆盖。
- F8、F11 由配置模块覆盖。
- F9、F11 由 Provider 工厂和入口错误处理覆盖。
- AC11 由 LLM 抽象模块和 TUI 依赖边界覆盖。
