package dev.mewcode.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 在现有文件中做一次精确替换。
 */
public final class EditFileTool implements Tool {
    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return "Use this tool to make one precise replacement inside an existing file. Choose it when the user wants "
                + "to replace one exact snippet with another snippet. The old_string must match exactly once; if it "
                + "matches zero times or more than once, the tool returns an error. Required parameters: path, "
                + "old_string, new_string. Before editing, first read the target file with read_file and confirm "
                + "that old_string is unique.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Schema.object(Schema.required("path", "old_string", "new_string"),
                "path", "string", "Exact local file path to edit.",
                "old_string", "string", "Exact existing text to replace. It should match exactly once.",
                "new_string", "string", "New text that replaces old_string.");
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    /**
     * 校验目标片段只出现一次后执行替换，避免误改多处内容。
     */
    @Override
    public Result execute(ToolContext context, String inputJson) {
        try {
            JsonNode args = JsonArgs.parse(inputJson);
            Path path = Paths.get(JsonArgs.requiredText(args, "path"));
            String oldString = JsonArgs.requiredText(args, "old_string");
            String newString = JsonArgs.requiredText(args, "new_string");
            if (!Files.exists(path)) {
                return Result.error("文件不存在: " + path);
            }
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            int count = countOccurrences(content, oldString);
            if (count == 0) {
                return Result.error("未找到匹配的内容");
            }
            if (count > 1) {
                return Result.error("匹配到 " + count + " 处，old_string 不唯一，请提供更长上下文");
            }
            Files.write(path, content.replace(oldString, newString).getBytes(StandardCharsets.UTF_8));
            return Result.ok("已修改: " + path);
        } catch (Exception e) {
            return Result.error("修改文件失败: " + e.getMessage());
        }
    }

    /**
     * 统计目标片段在全文中出现的次数，用于替换前的唯一性校验。
     */
    private int countOccurrences(String content, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int index = content.indexOf(needle, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + needle.length();
        }
    }
}
