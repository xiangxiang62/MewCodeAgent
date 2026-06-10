package dev.mewcode.agent.tool;

public final class Result {
    private final String content;
    private final boolean error;

    public Result(String content, boolean error) {
        this.content = content == null ? "" : content;
        this.error = error;
    }

    public static Result ok(String content) {
        return new Result(content, false);
    }

    public static Result error(String content) {
        return new Result(content, true);
    }

    public String content() {
        return content;
    }

    public boolean isError() {
        return error;
    }
}
