package dev.mewcode.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public final class ReadFileTool implements Tool {
    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Use this tool to read one local text file. Choose it when the user wants to open, inspect, quote, "
                + "review, or summarize a specific file such as README.md, pom.xml, or docs/toolSystem/spec.md. "
                + "Do not use it for searching many files by name or content; use glob or grep for that. "
                + "Required parameter: path.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Schema.object(Schema.required("path"),
                "path", "string", "Exact local file path to read, for example README.md or docs/toolSystem/spec.md.");
    }

    @Override
    public Result execute(ToolContext context, String inputJson) {
        try {
            JsonNode args = JsonArgs.parse(inputJson);
            Path path = Paths.get(JsonArgs.requiredText(args, "path"));
            if (!Files.exists(path)) {
                return Result.error("文件不存在: " + path);
            }
            if (Files.isDirectory(path)) {
                return Result.error("路径是目录，不能作为文件读取: " + path);
            }

            String content = new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
            String[] lines = content.split("\\R", -1);
            StringBuilder numbered = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                if (context.cancelled().get()) {
                    return Result.error("读取已取消");
                }
                if (i > 0) {
                    numbered.append(System.lineSeparator());
                }
                numbered.append(String.format("%6d\t%s", i + 1, lines[i]));
            }
            return Result.ok(Truncate.byLinesAndBytes(numbered.toString(), 2000, 256 * 1024));
        } catch (Exception e) {
            return Result.error("读取文件失败: " + e.getMessage());
        }
    }
}
