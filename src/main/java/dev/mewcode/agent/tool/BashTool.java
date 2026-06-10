package dev.mewcode.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class BashTool implements Tool {
    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "Use this tool to run one shell command in the current workspace. Choose it for build, test, list, "
                + "inspect, or other terminal tasks such as 'run mvn test', 'execute dir', or 'type a file'. "
                + "Required parameter: command. Returns combined output and exit code.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Schema.object(Schema.required("command"),
                "command", "string", "Exact shell command to run in the current workspace.");
    }

    @Override
    public Result execute(ToolContext context, String inputJson) {
        try {
            JsonNode args = JsonArgs.parse(inputJson);
            String command = JsonArgs.requiredText(args, "command");
            if (context.cancelled().get()) {
                return Result.error("命令已取消");
            }

            ProcessBuilder builder;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                builder = new ProcessBuilder("cmd", "/C", command);
            } else {
                builder = new ProcessBuilder("sh", "-c", command);
            }
            builder.redirectErrorStream(true);
            Process process = builder.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> copy(process.getInputStream(), output), "mewcode-bash-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(Registry.DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                context.cancelled().set(true);
                process.destroyForcibly();
                return Result.error("命令超时: " + command);
            }
            reader.join(1000);
            String stdout = new String(output.toByteArray(), StandardCharsets.UTF_8);
            String result = "exit_code: " + process.exitValue() + System.lineSeparator()
                    + "output:" + System.lineSeparator()
                    + stdout;
            return Result.ok(Truncate.byLinesAndBytes(result, 2000, 30 * 1024));
        } catch (Exception e) {
            return Result.error("命令执行失败: " + e.getMessage());
        }
    }

    private void copy(InputStream input, ByteArrayOutputStream output) {
        try {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        } catch (Exception ignored) {
            // The reader thread exits naturally when the process ends or is destroyed.
        }
    }
}
