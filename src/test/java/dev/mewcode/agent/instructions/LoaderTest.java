package dev.mewcode.agent.instructions;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 验证三层 MEWCODE 加载和 @include 展开规则。
 */
public class LoaderTest {
    /**
     * 三层优先级应按项目根、项目 .mewcode、用户 .mewcode 顺序拼接。
     */
    @Test
    public void loadsThreeLevelsInPriorityOrder() throws Exception {
        Path root = Files.createTempDirectory("mewcode-loader-root");
        Path home = Files.createTempDirectory("mewcode-loader-home");
        Files.writeString(root.resolve("MEWCODE.md"), "ROOT", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve(".mewcode"));
        Files.writeString(root.resolve(".mewcode").resolve("MEWCODE.md"), "PROJECT", StandardCharsets.UTF_8);
        Files.createDirectories(home.resolve(".mewcode"));
        Files.writeString(home.resolve(".mewcode").resolve("MEWCODE.md"), "USER", StandardCharsets.UTF_8);

        String text = new Loader(root, home, 5).load();

        assertTrue(text.indexOf("ROOT") < text.indexOf("PROJECT"));
        assertTrue(text.indexOf("PROJECT") < text.indexOf("USER"));
    }

    /**
     * 独占行 @include 应被展开。
     */
    @Test
    public void expandsInclude() throws Exception {
        Path root = Files.createTempDirectory("mewcode-loader-include");
        Files.createDirectories(root.resolve("rules"));
        Files.writeString(root.resolve("rules").resolve("style.md"), "STYLE", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("MEWCODE.md"), "@include rules/style.md", StandardCharsets.UTF_8);

        String text = new Loader(root, root, 5).load();

        assertTrue(text.contains("STYLE"));
        assertFalse(text.contains("@include"));
    }

    /**
     * 超深 include 应被保留为警告。
     */
    @Test
    public void warnsWhenDepthExceeded() throws Exception {
        Path root = Files.createTempDirectory("mewcode-loader-depth");
        Path a = root.resolve("a.md");
        Path b = root.resolve("b.md");
        Path c = root.resolve("c.md");
        Path d = root.resolve("d.md");
        Path e = root.resolve("e.md");
        Path f = root.resolve("f.md");
        Files.writeString(root.resolve("MEWCODE.md"), "@include a.md", StandardCharsets.UTF_8);
        Files.writeString(a, "@include b.md", StandardCharsets.UTF_8);
        Files.writeString(b, "@include c.md", StandardCharsets.UTF_8);
        Files.writeString(c, "@include d.md", StandardCharsets.UTF_8);
        Files.writeString(d, "@include e.md", StandardCharsets.UTF_8);
        Files.writeString(e, "@include f.md", StandardCharsets.UTF_8);
        Files.writeString(f, "TOO_DEEP", StandardCharsets.UTF_8);

        String text = new Loader(root, root, 5).load();

        assertTrue(text.contains("超过最大嵌套深度"));
    }

    /**
     * 越界 include 应返回范围警告。
     */
    @Test
    public void warnsWhenBoundaryEscaped() throws Exception {
        Path root = Files.createTempDirectory("mewcode-loader-boundary");
        Path outside = Files.createTempFile("mewcode-loader-outside", ".md");
        Files.writeString(root.resolve("MEWCODE.md"), "@include ../../" + outside.getFileName(), StandardCharsets.UTF_8);

        String text = new Loader(root, root.getParent(), 5).load();

        assertTrue(text.contains("路径超出允许范围") || text.contains("文件不存在"));
    }
}
