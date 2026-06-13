package dev.mewcode.agent.instructions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 负责加载三层 MEWCODE.md，并展开受限的 @include 指令。
 */
public final class Loader {
    private static final int DEFAULT_MAX_DEPTH = 5;

    private final Path projectRoot;
    private final Path userHome;
    private final int maxDepth;

    /**
     * 使用默认最大深度创建加载器。
     */
    public Loader(Path projectRoot) {
        this(projectRoot, Path.of(System.getProperty("user.home")), DEFAULT_MAX_DEPTH);
    }

    /**
     * 创建一个可注入用户目录和深度限制的加载器，便于测试。
     */
    public Loader(Path projectRoot, Path userHome, int maxDepth) {
        this.projectRoot = projectRoot == null ? Path.of("").toAbsolutePath().normalize() : projectRoot.toAbsolutePath().normalize();
        this.userHome = userHome == null ? Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
                : userHome.toAbsolutePath().normalize();
        this.maxDepth = Math.max(1, maxDepth);
    }

    /**
     * 按项目根、项目 .mewcode、用户 .mewcode 的顺序加载指令文本。
     */
    public String load() throws IOException {
        List<String> parts = new ArrayList<String>();
        appendIfPresent(parts, projectRoot.resolve("MEWCODE.md"), projectRoot);
        appendIfPresent(parts, projectRoot.resolve(".mewcode").resolve("MEWCODE.md"), projectRoot.resolve(".mewcode"));
        appendIfPresent(parts, userHome.resolve(".mewcode").resolve("MEWCODE.md"), userHome.resolve(".mewcode"));
        return String.join("\n\n", parts);
    }

    /**
     * 在文件存在时加载并追加展开后的内容。
     */
    private void appendIfPresent(List<String> parts, Path file, Path boundary) throws IOException {
        if (!Files.exists(file) || Files.isDirectory(file)) {
            return;
        }
        String content = loadFile(file, boundary, 1, new HashSet<Path>(), new ArrayDeque<Path>());
        if (content != null && !content.trim().isEmpty()) {
            parts.add(content.trim());
        }
    }

    /**
     * 递归加载单个文件并处理独占行的 @include。
     */
    private String loadFile(Path file, Path boundary, int depth, Set<Path> visited, Deque<Path> stack) throws IOException {
        if (depth > maxDepth) {
            return warning("<!-- @include 超过最大嵌套深度，已跳过: " + file + " -->");
        }
        if (!Files.exists(file) || Files.isDirectory(file)) {
            return warning("<!-- @include 文件不存在，已跳过: " + file + " -->");
        }
        Path absolute = file.toAbsolutePath().normalize();
        Path allowedBoundary = boundary.toAbsolutePath().normalize();
        if (!absolute.startsWith(allowedBoundary)) {
            return warning("<!-- @include 路径超出允许范围，已跳过: " + file + " -->");
        }
        if (visited.contains(absolute)) {
            return warning("<!-- @include 检测到循环引用，已跳过: " + relativeTrace(stack, absolute) + " -->");
        }
        byte[] bytes = Files.readAllBytes(absolute);
        if (looksBinary(bytes)) {
            return warning("<!-- @include 检测到二进制文件，已跳过: " + file + " -->");
        }
        visited.add(absolute);
        stack.addLast(absolute);
        try {
            List<String> rendered = new ArrayList<String>();
            List<String> lines = Files.readAllLines(absolute, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.startsWith("@include ") && line.trim().equals(line)) {
                    Path includePath = absolute.getParent().resolve(line.substring("@include ".length()).trim()).normalize();
                    rendered.add(loadFile(includePath, allowedBoundary, depth + 1, visited, stack));
                } else {
                    rendered.add(line);
                }
            }
            return String.join("\n", rendered);
        } finally {
            stack.removeLast();
            visited.remove(absolute);
        }
    }

    /**
     * 将警告文本规范化为可直接拼接的字符串。
     */
    private String warning(String text) {
        return text == null ? "" : text;
    }

    /**
     * 根据前 512 字节中的空字节粗略判断二进制文件。
     */
    private boolean looksBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, 512);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成简短的循环引用轨迹，便于排查 include 链路。
     */
    private String relativeTrace(Deque<Path> stack, Path current) {
        List<String> parts = new ArrayList<String>();
        for (Path path : stack) {
            parts.add(path.getFileName().toString());
        }
        parts.add(current.getFileName().toString());
        return String.join(" -> ", parts);
    }
}
