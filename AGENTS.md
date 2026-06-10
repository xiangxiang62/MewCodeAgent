# MewCode

我正在构建一个终端 AI 编程助手（类似 Claude Code），项目名叫 MewCode，使用 Java 实现。

---

## 语言

中文回答，给每个方法上都打上中文注释。

---

## 测试（重要）

开发完成后必须进行端到端测试（E2E Test），但根据运行环境选择不同方式：

---

### Linux / macOS 环境（推荐 tmux）

如果当前系统支持 tmux：

1. 在 tmux 中启动 MewCode
2. 输入一段真实对话请求
3. 观察是否正确调用工具、生成回复
4. 对照 checklist.md 逐项验收

---

### Windows 环境（当前环境）

如果当前环境为 Windows 或无法使用 tmux：

使用以下替代方案进行端到端测试：

####  启动方式

```bash
java -jar target/mewcode-agent.jar
```
或：
```bash
mvn spring-boot:run
```

####  测试方式
在 IDE Console / Terminal 输入真实对话
或使用 REST API（如果有）
或使用单元测试模拟用户输入
检查：
工具是否正确调用
是否生成合理回复
是否符合 checklist.md


####  验收标准
功能等价于 tmux 测试流程
所有工具调用正常
无异常退出
输出符合预期
