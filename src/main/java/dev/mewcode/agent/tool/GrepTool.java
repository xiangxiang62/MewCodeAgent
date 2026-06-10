package dev.mewcode.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

public final class GrepTool implements Tool {
    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "Use this tool to search inside file contents for text or patterns. Choose it for requests like "
                + "'search ToolAgent', 'find where api_key appears', or 'grep TODO in Java files'. Do not use it to "
                + "find files by name only; use glob for that. Required parameter: pattern. Optional parameters: path, glob.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Schema.object(Schema.required("pattern"),
                "pattern", "string", "Text or Java regex pattern to search for inside files.",
                "path", "string", "Optional search root path. Use '.' for the workspace root.",
                "glob", "string", "Optional filename filter such as '**/*.java' to restrict searched files.");
    }

    @Override
    public Result execute(ToolContext context, String inputJson) {
        try {
            JsonNode args = JsonArgs.parse(inputJson);
            Pattern pattern;
            try {
                pattern = Pattern.compile(JsonArgs.requiredText(args, "pattern"));
            } catch (PatternSyntaxException e) {
                return Result.error("正则表达式非法: " + e.getMessage());
            }
            Path root = Paths.get(JsonArgs.optionalText(args, "path", "."));
            String glob = JsonArgs.optionalText(args, "glob", null);
            PathMatcher matcher = glob == null ? null : FileSystems.getDefault().getPathMatcher("glob:" + glob);
            List<String> hits = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile).forEach(path -> {
                    if (context.cancelled().get() || hits.size() >= 100) {
                        return;
                    }
                    Path rel = root.relativize(path);
                    String normalized = rel.toString().replace('\\', '/');
                    if (matcher != null && !matcher.matches(Paths.get(normalized))) {
                        return;
                    }
                    searchFile(pattern, path, hits);
                });
            }
            if (hits.isEmpty()) {
                return Result.ok("无命中");
            }
            if (hits.size() >= 100) {
                hits.add("[truncated]");
            }
            return Result.ok(String.join(System.lineSeparator(), hits));
        } catch (Exception e) {
            return Result.error("搜索内容失败: " + e.getMessage());
        }
    }

    private void searchFile(Pattern pattern, Path path, List<String> hits) {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null && hits.size() < 100) {
                lineNumber++;
                if (line.length() > 1024 * 1024) {
                    continue;
                }
                if (pattern.matcher(line).find()) {
                    hits.add(path + ":" + lineNumber + ":" + line);
                }
            }
        } catch (Exception ignored) {
            // Skip unreadable or non-UTF-8 files so one file does not stop the whole search.
        }
    }
}
