package dev.mewcode.agent.runtime;

/**
 * 抽象工具执行过程在界面层的展示行为。
 */
public interface ToolDisplay {
    /**
     * 工具开始执行时调用。
     */
    void onToolStart(String name, String args);

    /**
     * 工具结束执行时调用。
     */
    void onToolEnd(String name, String args, String result, boolean error);
}
