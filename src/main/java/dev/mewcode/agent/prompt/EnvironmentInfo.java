package dev.mewcode.agent.prompt;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 负责采集并渲染运行时环境信息。
 */
public final class EnvironmentInfo {
    private final String workingDir;
    private final String platform;
    private final String date;
    private final String gitStatus;
    private final String version;
    private final String model;

    /**
     * 创建环境信息对象。
     */
    public EnvironmentInfo(String workingDir, String platform, String date, String gitStatus, String version,
            String model) {
        this.workingDir = emptyIfNull(workingDir);
        this.platform = emptyIfNull(platform);
        this.date = emptyIfNull(date);
        this.gitStatus = emptyIfNull(gitStatus);
        this.version = emptyIfNull(version);
        this.model = emptyIfNull(model);
    }

    /**
     * 从当前进程环境中采集工作目录、平台、日期和 git 状态。
     */
    public static EnvironmentInfo gather(String version, String model) {
        String workingDir = valueOrEmpty(System.getProperty("user.dir"));
        String platform = valueOrEmpty(System.getProperty("os.name"));
        String date = LocalDate.now().toString();
        String gitStatus = readGitStatus();
        return new EnvironmentInfo(workingDir, platform, date, gitStatus, version, model);
    }

    /**
     * 将环境信息渲染成给模型阅读的文本块。
     */
    public String render() {
        List<String> lines = new ArrayList<String>();
        lines.add("Environment Information:");
        append(lines, "Working Directory", workingDir);
        append(lines, "Platform", platform);
        append(lines, "Date", date);
        append(lines, "Git Status", gitStatus);
        append(lines, "App Version", version);
        append(lines, "Model", model);
        return String.join(System.lineSeparator(), lines);
    }

    /**
     * 仅在字段有值时追加一行，避免输出空标签。
     */
    private static void append(List<String> lines, String label, String value) {
        if (value != null && !value.trim().isEmpty()) {
            lines.add(label + ": " + value);
        }
    }

    /**
     * 读取当前目录的简化 git 状态。
     * 这里限制等待时间，避免在异常环境下拖慢首轮对话。
     */
    private static String readGitStatus() {
        Process process = null;
        try {
            process = new ProcessBuilder("git", "status", "--porcelain").redirectErrorStream(true).start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                if (!finished) {
                    process.destroyForcibly();
                }
                return "";
            }
            String output = readStream(process.getInputStream()).trim();
            if (output.isEmpty()) {
                return "clean";
            }
            String[] lines = output.split("\\R");
            if (lines.length == 1) {
                return lines[0];
            }
            return lines[0] + " (+" + (lines.length - 1) + " more)";
        } catch (Exception ignored) {
            return "";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /**
     * 把进程输出完整读成 UTF-8 文本。
     */
    private static String readStream(InputStream inputStream) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 把可能为 null 的输入归一化为空字符串。
     */
    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 把字段值安全归一化，避免构造器里保存 null。
     */
    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
