package dev.mewcode.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 写入整个文件内容；若目录不存在则自动创建。
 */
public final class WriteFileTool implements Tool {
    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Use this tool to create a text file or fully overwrite an existing text file. Choose it when the user "
                + "wants to create a new file or replace the whole file content. Do not use it for a small targeted "
                + "change inside an existing file; use edit_file for that. Required parameters: path, content.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Schema.object(Schema.required("path", "content"),
                "path", "string", "Exact local file path to create or overwrite.",
                "content", "string", "Full text content to write into the file.");
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    /**
     * 将给定文本完整写入目标文件，并在必要时补齐父目录。
     */
    @Override
    public Result execute(ToolContext context, String inputJson) {
        try {
            JsonNode args = JsonArgs.parse(inputJson);
            Path path = Paths.get(JsonArgs.requiredText(args, "path"));
            String content = JsonArgs.requiredText(args, "content");
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, content.getBytes(StandardCharsets.UTF_8));
            return Result.ok("已写入 " + path + "，" + content.getBytes(StandardCharsets.UTF_8).length + " 字节");
        } catch (Exception e) {
            return Result.error("写入文件失败: " + e.getMessage());
        }
    }
}
