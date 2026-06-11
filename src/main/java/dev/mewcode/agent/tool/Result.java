package dev.mewcode.agent.tool;

/**
 * 工具执行结果，统一用文本内容加错误标记表示。
 */
public final class Result {
    private final String content;
    private final boolean error;

    /**
     * 创建一条工具结果。
     */
    public Result(String content, boolean error) {
        this.content = content == null ? "" : content;
        this.error = error;
    }

    /**
     * 创建成功结果。
     */
    public static Result ok(String content) {
        return new Result(content, false);
    }

    /**
     * 创建失败结果。
     */
    public static Result error(String content) {
        return new Result(content, true);
    }

    /**
     * 返回结果文本。
     */
    public String content() {
        return content;
    }

    /**
     * 标记结果是否为错误。
     */
    public boolean isError() {
        return error;
    }
}
