# MewCode 第一阶段纯对话 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
| --- | --- | --- |
| 保留/修改 | `pom.xml` | Maven 构建、依赖、可运行 jar 打包 |
| 保留/修改 | `README.md` | 运行说明、配置说明、命令说明 |
| 保留/修改 | `config.yaml` | 本地运行配置 |
| 保留 | `spec.md` | 已确认需求 |
| 保留 | `plan.md` | 已确认技术设计 |
| 新建 | `task.md` | 实现任务拆解 |
| 修改 | `src/main/java/dev/mewcode/agent/MewCodeApp.java` | 程序入口、配置路径解析、启动流程 |
| 修改 | `src/main/java/dev/mewcode/agent/config/AppConfig.java` | 应用配置结构 |
| 修改 | `src/main/java/dev/mewcode/agent/config/LlmConfig.java` | LLM 配置结构 |
| 修改 | `src/main/java/dev/mewcode/agent/config/ThinkingConfig.java` | Claude thinking 配置结构 |
| 修改 | `src/main/java/dev/mewcode/agent/config/ConfigLoader.java` | YAML 加载、环境变量展开、校验 |
| 修改 | `src/main/java/dev/mewcode/agent/ui/ChatConsole.java` | 终端交互、多轮对话、退出和清空 |
| 修改 | `src/main/java/dev/mewcode/agent/llm/ChatMessage.java` | 对话消息结构 |
| 修改 | `src/main/java/dev/mewcode/agent/llm/Role.java` | 消息角色定义 |
| 修改 | `src/main/java/dev/mewcode/agent/llm/LlmProvider.java` | Provider 统一接口 |
| 修改 | `src/main/java/dev/mewcode/agent/llm/StreamCallback.java` | 流式增量回调 |
| 修改 | `src/main/java/dev/mewcode/agent/llm/LlmProviderFactory.java` | 根据协议选择 Provider |
| 修改 | `src/main/java/dev/mewcode/agent/llm/http/SseClient.java` | SSE 帧读取 |
| 修改 | `src/main/java/dev/mewcode/agent/llm/openai/OpenAiProvider.java` | OpenAI 流式 Provider |
| 修改 | `src/main/java/dev/mewcode/agent/llm/anthropic/AnthropicProvider.java` | Anthropic 流式 Provider |

## T1: 确认构建和运行入口

**文件：** `pom.xml`, `src/main/java/dev/mewcode/agent/MewCodeApp.java`

**依赖：** 无

**步骤：**
1. 确认 Maven 项目能编译所有 Java 源码。
2. 确认 jar manifest 指向主入口。
3. 确认入口支持命令行配置路径。
4. 确认入口支持无参数时自动查找默认配置路径。
5. 确认配置缺失时输出清晰提示并退出。

**验证：** 运行 `mvn package`，期望构建成功并生成 `target/mewcode-agent-0.1.0-SNAPSHOT.jar`。

## T2: 实现 YAML 配置加载和校验

**文件：** `src/main/java/dev/mewcode/agent/config/*.java`

**依赖：** T1

**步骤：**
1. 定义应用配置和 LLM 配置结构。
2. 映射 YAML 字段 `base_url`、`api_key`、`max_tokens`、`budget_tokens`。
3. 支持 `${ENV_NAME}` 环境变量占位符展开。
4. 校验 `protocol`、`model`、`base_url`、`api_key` 四个必填字段。
5. 为可选字段提供合理默认值。

**验证：** 使用包含四个核心字段的 `config.yaml` 启动，期望进入后续启动流程；删除任一必填字段后启动，期望错误信息指出缺失字段。

## T3: 定义 LLM 公共抽象

**文件：** `src/main/java/dev/mewcode/agent/llm/*.java`

**依赖：** T2

**步骤：**
1. 定义对话消息结构，包含角色和内容。
2. 定义用户和助手两种角色及供应商协议值。
3. 定义流式文本回调。
4. 定义统一 Provider 接口，包含名称和流式聊天方法。
5. 定义 Provider 工厂，根据 `protocol` 创建具体 Provider。
6. 对未知协议抛出清晰错误。

**验证：** 使用 `protocol: openai` 和 `protocol: anthropic` 分别启动，期望创建对应 Provider；使用未知协议启动，期望看到不支持协议的错误。

## T4: 实现 SSE 读取模块

**文件：** `src/main/java/dev/mewcode/agent/llm/http/SseClient.java`

**依赖：** T3

**步骤：**
1. 从响应输入流逐行读取内容。
2. 收集以 `data:` 开头的行。
3. 在空行处完成一个 SSE 事件并交给调用方。
4. 支持同一事件内多行 `data:` 拼接。
5. 避免等待完整响应结束后再统一处理。

**验证：** 使用模拟 SSE 输入流，期望每个事件 data 被按顺序回调；真实 API 调用时，终端能逐步收到文本增量。

## T5: 实现 OpenAI Provider

**文件：** `src/main/java/dev/mewcode/agent/llm/openai/OpenAiProvider.java`

**依赖：** T3, T4

**步骤：**
1. 根据配置生成 OpenAI Chat Completions endpoint。
2. 将内部消息历史转换为 OpenAI `messages`。
3. 构造包含 `model`、`stream`、`messages` 的 JSON 请求体。
4. 使用 Bearer Token 设置认证头。
5. 发起 HTTP POST 请求并读取 SSE 响应。
6. 从 `choices[0].delta.content` 提取文本增量。
7. 将增量传给回调，并累计完整助手回复。

**验证：** 使用有效 OpenAI 配置运行，输入一句话，期望终端逐步打印模型回复，并在第二轮能引用第一轮上下文。

## T6: 实现 Anthropic Provider

**文件：** `src/main/java/dev/mewcode/agent/llm/anthropic/AnthropicProvider.java`

**依赖：** T3, T4

**步骤：**
1. 根据配置生成 Anthropic Messages endpoint。
2. 将内部消息历史转换为 Anthropic `messages`。
3. 构造包含 `model`、`max_tokens`、`stream`、`messages` 的 JSON 请求体。
4. 使用 `x-api-key` 和 Anthropic API version 设置请求头。
5. 当 thinking 配置启用时附加 thinking 参数。
6. 发起 HTTP POST 请求并读取 SSE 响应。
7. 从 `content_block_delta` 的 `text_delta` 中提取文本增量。
8. 将增量传给回调，并累计完整助手回复。

**验证：** 使用有效 Anthropic 配置运行，输入一句话，期望终端逐步打印模型回复；启用 thinking 后，请求体包含 thinking 参数。

## T7: 实现终端对话循环

**文件：** `src/main/java/dev/mewcode/agent/ui/ChatConsole.java`

**依赖：** T3, T5, T6

**步骤：**
1. 初始化终端输入输出。
2. 显示启动信息、Provider 名称和配置路径。
3. 循环读取用户输入。
4. 忽略空输入。
5. 支持 `/exit` 和 `/quit` 正常退出。
6. 支持 `/clear` 清空当前消息历史。
7. 普通输入加入历史并调用 Provider。
8. 在回调中立即打印模型文本增量。
9. 流结束后将完整助手回复加入历史。

**验证：** 启动程序后输入普通问题，期望有流式回复；输入 `/clear` 后继续提问，期望不携带清空前上下文；输入 `/exit` 后程序结束。

## T8: 更新文档和示例配置

**文件：** `README.md`, `config.yaml`

**依赖：** T1-T7

**步骤：**
1. 说明 `config.yaml` 的用途。
2. 说明 OpenAI 配置示例。
3. 说明 Anthropic 配置示例。
4. 说明 Claude thinking 可选配置。
5. 说明 Maven 构建命令。
6. 说明 cmd 中的运行命令。
7. 说明 `/exit`、`/quit`、`/clear`。

**验证：** 按 README 从 cmd 运行，期望能完成构建并启动程序。

## T9: 集成验证

**文件：** 全项目

**依赖：** T1-T8

**步骤：**
1. 运行 Maven 构建。
2. 无配置启动，观察是否输出配置提示。
3. 使用 OpenAI 配置进行一轮真实对话。
4. 使用 Anthropic 配置进行一轮真实对话。
5. 进行两轮上下文引用测试。
6. 测试 `/clear` 和 `/exit`。

**验证：** 对照 `checklist.md` 执行验收项，所有关键行为可观察通过。

## 执行顺序

```text
T1 -> T2 -> T3 -> T4
              -> T5
              -> T6
T5 + T6 -> T7 -> T8 -> T9
```
