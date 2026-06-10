package dev.mewcode.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class GlobTool implements Tool {
    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "Use this tool to find files by filename pattern or extension across the workspace. Choose it for "
                + "requests like 'find all Java files', 'list markdown files', or 'find YAML configs'. Do not use it "
                + "to search inside file contents; use grep for that. Required parameter: pattern. Optional parameter: path.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Schema.object(Schema.required("pattern"),
                "pattern", "string", "Glob pattern, for example '**/*.java' or '**/*.md'.",
                "path", "string", "Optional search root path. Use '.' for the workspace root.");
    }

    @Override
    public Result execute(ToolContext context, String inputJson) {
        try {
            JsonNode args = JsonArgs.parse(inputJson);
            String pattern = JsonArgs.requiredText(args, "pattern").replace('\\', '/');
            Path root = Paths.get(JsonArgs.optionalText(args, "path", "."));
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<String> matches = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile).forEach(path -> {
                    if (context.cancelled().get() || matches.size() >= 100) {
                        return;
                    }
                    Path rel = root.relativize(path);
                    String normalized = rel.toString().replace('\\', '/');
                    if (matcher.matches(Paths.get(normalized))) {
                        matches.add(path.toString());
                    }
                });
            }
            Collections.sort(matches);
            if (matches.isEmpty()) {
                return Result.ok("无匹配");
            }
            return Result.ok(String.join(System.lineSeparator(), matches));
        } catch (Exception e) {
            return Result.error("查找文件失败: " + e.getMessage());
        }
    }
}
