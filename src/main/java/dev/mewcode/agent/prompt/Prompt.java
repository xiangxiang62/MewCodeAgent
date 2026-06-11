package dev.mewcode.agent.prompt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 负责装配系统提示词的稳定部分。
 */
public final class Prompt {
    public static final String MODE_STATUS_NORMAL = "当前是执行模式，可以使用全部工具按步骤完成任务。";
    public static final String MODE_STATUS_PLAN = "当前是计划模式，只能使用只读工具进行调研，并输出计划，不能执行写入、编辑或命令工具。";
    public static final String EMPTY_WORKSPACE_REMINDER = ""
            + "The workspace appears empty or does not contain an existing project yet. "
            + "Do not keep probing for missing files. "
            + "Answer directly with a concrete implementation plan in Chinese for building the requested project from scratch.";
    public static final String EMPTY_WORKSPACE_MARKER = "[MEWCODE_EMPTY_WORKSPACE]";

    private Prompt() {
    }

    /**
     * 构建完整稳定系统提示词。
     */
    public static String buildSystemPrompt() {
        List<PromptModule> modules = new ArrayList<PromptModule>();
        modules.addAll(fixedModules());
        modules.addAll(optionalModules());
        return assembleSystem(modules);
    }

    /**
     * 按优先级排序后拼接各提示模块，并用空行分隔。
     */
    public static String assembleSystem(List<PromptModule> modules) {
        List<PromptModule> ordered = new ArrayList<PromptModule>(modules);
        Collections.sort(ordered, Comparator.comparingInt(PromptModule::priority));
        List<String> parts = new ArrayList<String>();
        for (PromptModule module : ordered) {
            if (module.content() != null && !module.content().trim().isEmpty()) {
                parts.add(module.content().trim());
            }
        }
        return String.join("\n\n", parts);
    }

    /**
     * 返回固定启用的系统提示模块。
     */
    public static List<PromptModule> fixedModules() {
        List<PromptModule> modules = new ArrayList<PromptModule>();
        modules.add(new PromptModule("identity", 10,
                "You are MewCode, a terminal coding assistant."));
        modules.add(new PromptModule("system-constraints", 20,
                "Never reveal secrets such as API keys or sensitive config values. "
                        + "Be careful with destructive actions and stay within the local workspace context."));
        modules.add(new PromptModule("task-mode", 30,
                "Keep using tools across multiple steps to make progress, and only give your final concise answer once the task is complete."));
        modules.add(new PromptModule("action-execution", 40,
                "When the user asks about local files, project contents, code search, or shell actions, use tools instead of pretending you already inspected the workspace. "
                        + "If a tool fails, use the structured error to adjust your next step instead of stopping."));
        modules.add(new PromptModule("tool-usage", 50,
                "Read the available tool descriptions carefully before choosing a tool. "
                        + "Fill parameters precisely from the user's request. "
                        + "If a file path is implied, infer the most likely local path from the workspace context. "
                        + "Prefer dedicated tools such as read_file, glob, and grep instead of shell commands for file inspection and search. "
                        + "Before editing a file, you must first read it with read_file."));
        modules.add(new PromptModule("tone-style", 60,
                "Answer in concise Chinese after getting tool results."));
        modules.add(new PromptModule("text-output", 70,
                "Keep replies direct and practical. Use Markdown only when it makes the result clearer."));
        return modules;
    }

    /**
     * 返回可选模块占位；为空时会在装配阶段自动跳过。
     */
    public static List<PromptModule> optionalModules() {
        List<PromptModule> modules = new ArrayList<PromptModule>();
        modules.add(new PromptModule("custom-instructions", 80, ""));
        modules.add(new PromptModule("active-skills", 90, ""));
        modules.add(new PromptModule("long-term-memory", 100, ""));
        return modules;
    }
}
