package dev.mewcode.agent.runtime;

public interface ToolDisplay {
    void onToolStart(String name, String args);

    void onToolEnd(String name, String args, String result, boolean error);
}
