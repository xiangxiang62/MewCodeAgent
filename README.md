# MewCode

## 中文说明

MewCode 是一个使用 Java 开发的命令行 AI 助手。当前版本只实现交互式多轮对话：不做 tool use、不读写文件、不编辑代码。

### 功能

- 终端交互式聊天界面
- SSE 流式输出，模型回复会逐步打印
- 当前进程内的多轮对话记忆
- 统一 Provider 抽象，方便后续接入更多 LLM 后端
- 支持 OpenAI Chat Completions 协议
- 支持 Anthropic Claude Messages 协议
- 支持 Claude extended thinking 可选配置

### 配置

MewCode 会按下面顺序查找配置文件：

1. 命令行第一个参数
2. `MEWCODE_CONFIG` 环境变量
3. 当前目录下的 `config.yaml` 或 `config.yml`
4. 用户目录下的 `~/.mewcode/config.yaml` 或 `~/.mewcode/config.yml`

本地开发时，可以复制示例配置并填写真实 API Key：

```cmd
copy config.example.yaml config.yaml
notepad config.yaml
```

OpenAI 示例：

```yaml
llm:
  protocol: openai
  model: gpt-4.1-mini
  base_url: https://api.openai.com/v1
  api_key: sk-...
  max_tokens: 4096
```

Anthropic Claude 示例：

```yaml
llm:
  protocol: anthropic
  model: claude-sonnet-4-20250514
  base_url: https://api.anthropic.com/v1
  api_key: sk-ant-...
  max_tokens: 4096
  thinking:
    enabled: true
    budget_tokens: 1024
    display: omitted
```

四个必填字段是 `protocol`、`model`、`base_url`、`api_key`。Claude extended thinking 通过 `thinking` 配置开启。

### 运行

在 Windows cmd 中运行：

```cmd
cd /d D:\AI-Project\MewcodeAgent
mvn package
java -jar target\mewcode-agent-0.1.0-SNAPSHOT.jar config.yaml
```

聊天命令：

- `/exit` 或 `/quit`：退出对话
- `/clear`：重置当前对话上下文

## English

MewCode is a Java command-line AI assistant. The current version only implements interactive ch01_Multi-turn chat: no tool use, no file operations, and no code editing.

### Features

- Interactive terminal chat UI
- SSE streaming output
- In-process ch01_Multi-turn conversation memory
- Unified Provider abstraction for future LLM backends
- OpenAI Chat Completions protocol
- Anthropic Claude Messages protocol
- Optional Claude extended thinking config

### Configuration

MewCode resolves the config path in this order:

1. First command-line argument
2. `MEWCODE_CONFIG` environment variable
3. `./config.yaml` or `./config.yml`
4. `~/.mewcode/config.yaml` or `~/.mewcode/config.yml`

For local development, copy the example config and fill in your real API key:

```cmd
copy config.example.yaml config.yaml
notepad config.yaml
```

OpenAI example:

```yaml
llm:
  protocol: openai
  model: gpt-4.1-mini
  base_url: https://api.openai.com/v1
  api_key: sk-...
  max_tokens: 4096
```

Anthropic Claude example:

```yaml
llm:
  protocol: anthropic
  model: claude-sonnet-4-20250514
  base_url: https://api.anthropic.com/v1
  api_key: sk-ant-...
  max_tokens: 4096
  thinking:
    enabled: true
    budget_tokens: 1024
    display: omitted
```

The four required fields are `protocol`, `model`, `base_url`, and `api_key`. Claude extended thinking is enabled through the `thinking` object.

### Run

From Windows cmd:

```cmd
cd /d D:\AI-Project\MewcodeAgent
mvn package
java -jar target\mewcode-agent-0.1.0-SNAPSHOT.jar config.yaml
```

Chat commands:

- `/exit` or `/quit`: exit chat
- `/clear`: reset the current conversation context
