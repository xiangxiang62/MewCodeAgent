# MewCode 第一阶段纯对话 Checklist

> 每一项都通过运行程序、观察终端输出或检查请求行为来验证。验收时先收集证据，再判断是否通过。

## 实现完整性

- [ ] 程序可以从命令行启动并进入交互式输入状态（验证：运行 `java -jar target/mewcode-agent-0.1.0-SNAPSHOT.jar config.yaml`，看到启动信息和 `>` 输入提示）
- [ ] 程序支持通过 YAML 配置选择 Provider（验证：分别设置 `protocol: openai` 和 `protocol: anthropic`，启动信息显示对应 Provider）
- [ ] YAML 配置包含四个核心字段并被正确读取（验证：配置 `protocol`、`model`、`base_url`、`api_key` 后程序能进入聊天流程）
- [ ] 配置文件缺失时给出可操作提示（验证：在没有默认配置且不传参数时启动，看到创建或指定配置文件的提示）
- [ ] 必填配置字段缺失时指出具体字段（验证：删除 `api_key` 后启动，看到缺失 `llm.api_key` 的错误）
- [ ] Provider 抽象存在且 TUI 不直接依赖供应商请求格式（验证：检查 TUI 模块只调用统一 Provider 接口）

## OpenAI 行为

- [ ] OpenAI Provider 能发起流式聊天请求（验证：使用有效 OpenAI 配置输入一句话，终端开始输出回复）
- [ ] OpenAI 回复是增量打印，不是等待完整结果后一次性输出（验证：使用较长回答请求，观察文本逐步出现）
- [ ] OpenAI Provider 能累计完整助手回复并加入历史（验证：第一轮让模型记住一个短词，第二轮询问该短词，模型能答出）
- [ ] OpenAI endpoint 兼容 base URL 和完整 endpoint 两种配置（验证：分别配置 `https://api.openai.com/v1` 和完整 `/chat/completions` 地址，均能进入请求流程）

## Anthropic 行为

- [ ] Anthropic Provider 能发起流式 Messages 请求（验证：使用有效 Anthropic 配置输入一句话，终端开始输出回复）
- [ ] Anthropic 回复是增量打印，不是等待完整结果后一次性输出（验证：使用较长回答请求，观察文本逐步出现）
- [ ] Anthropic Provider 能累计完整助手回复并加入历史（验证：第一轮让模型记住一个短词，第二轮询问该短词，模型能答出）
- [ ] Anthropic endpoint 兼容 base URL 和完整 endpoint 两种配置（验证：分别配置 `https://api.anthropic.com/v1` 和完整 `/messages` 地址，均能进入请求流程）
- [ ] Claude extended thinking 配置启用时被传入请求（验证：配置 `thinking.enabled: true` 和 `budget_tokens` 后，调试请求体或日志中包含 thinking 对象）

## 终端交互

- [ ] 空输入不会触发模型请求（验证：直接回车，程序继续等待输入且没有输出模型回复）
- [ ] `/exit` 能正常退出（验证：输入 `/exit`，进程结束）
- [ ] `/quit` 能正常退出（验证：输入 `/quit`，进程结束）
- [ ] `/clear` 能清空当前会话历史（验证：先让模型记住一个短词，输入 `/clear` 后再问该短词，模型不应依赖清空前上下文回答）
- [ ] 模型流式输出结束后终端回到下一轮输入状态（验证：一次回复结束后看到新的输入提示并可继续输入）

## 编译与打包

- [ ] Maven 构建成功（验证：运行 `mvn package`，看到 `BUILD SUCCESS`）
- [ ] 可运行 jar 被生成（验证：`target/mewcode-agent-0.1.0-SNAPSHOT.jar` 存在）
- [ ] jar manifest 指向正确入口（验证：直接运行 jar 能进入 MewCode 启动流程）
- [ ] README 中的 cmd 运行步骤可执行（验证：按 README 在 cmd 中从 `cd /d D:\AI-Project\MewcodeAgent` 开始运行）

## 端到端场景

- [ ] 场景 1：OpenAI 单轮对话（验证：OpenAI 配置下启动，输入“用一句话介绍你自己”，看到流式中文或英文回复）
- [ ] 场景 2：Anthropic 单轮对话（验证：Anthropic 配置下启动，输入“用一句话介绍你自己”，看到流式回复）
- [ ] 场景 3：多轮上下文（验证：输入“记住暗号是 mew-blue”，再输入“暗号是什么？”，模型回答包含 `mew-blue`）
- [ ] 场景 4：清空上下文（验证：完成场景 3 后输入 `/clear`，再问“暗号是什么？”，模型不应基于旧上下文确定回答）
- [ ] 场景 5：配置错误恢复（验证：使用缺失字段的配置启动看到错误，补齐字段后再次启动能进入聊天）

## 不做范围确认

- [ ] 程序不会读写用户项目文件（验证：普通聊天过程中没有文件操作提示或副作用）
- [ ] 程序不会执行 shell 命令（验证：普通聊天过程中没有命令执行行为）
- [ ] 程序不会尝试 tool use（验证：模型回复只作为文本展示，不触发工具调用流程）
- [ ] 程序不会自动修改代码或生成 patch（验证：聊天过程中源码文件不因模型回复自动变化）
