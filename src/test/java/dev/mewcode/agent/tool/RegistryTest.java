package dev.mewcode.agent.tool;

import dev.mewcode.agent.llm.ToolDefinition;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RegistryTest {
    @Test
    public void definitionsReturnsSixOrdered() {
        Registry registry = Registry.defaultRegistry();
        List<ToolDefinition> definitions = registry.definitions();

        assertEquals(6, definitions.size());
        assertEquals("read_file", definitions.get(0).name());
        assertEquals("write_file", definitions.get(1).name());
        assertEquals("edit_file", definitions.get(2).name());
        assertEquals("bash", definitions.get(3).name());
        assertEquals("glob", definitions.get(4).name());
        assertEquals("grep", definitions.get(5).name());
        assertTrue(registry.get("read_file").isPresent());
        assertFalse(registry.get("missing").isPresent());
    }

    @Test
    public void readFileExistsAndMissing() throws Exception {
        Path file = Files.createTempFile("mewcode-read", ".txt");
        Files.write(file, "hello\nworld".getBytes(StandardCharsets.UTF_8));
        Registry registry = Registry.defaultRegistry();

        Result ok = registry.execute(ToolContext.fresh(), "read_file", "{\"path\":\"" + escape(file) + "\"}");
        Result missing = registry.execute(ToolContext.fresh(), "read_file", "{\"path\":\"no-such-file.txt\"}");

        assertFalse(ok.isError());
        assertTrue(ok.content().contains("     1\thello"));
        assertTrue(missing.isError());
    }

    @Test
    public void writeFileCreatesNestedDir() throws Exception {
        Path dir = Files.createTempDirectory("mewcode-write");
        Path file = dir.resolve("a/b/c.txt");
        Registry registry = Registry.defaultRegistry();

        Result result = registry.execute(ToolContext.fresh(), "write_file",
                "{\"path\":\"" + escape(file) + "\",\"content\":\"ok\"}");

        assertFalse(result.isError());
        assertEquals("ok", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    @Test
    public void editFileHandlesZeroUniqueAndMultiple() throws Exception {
        Path file = Files.createTempFile("mewcode-edit", ".txt");
        Registry registry = Registry.defaultRegistry();

        Files.write(file, "a b c".getBytes(StandardCharsets.UTF_8));
        Result unique = registry.execute(ToolContext.fresh(), "edit_file",
                "{\"path\":\"" + escape(file) + "\",\"old_string\":\"b\",\"new_string\":\"B\"}");
        assertFalse(unique.isError());
        assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains("B"));

        Result zero = registry.execute(ToolContext.fresh(), "edit_file",
                "{\"path\":\"" + escape(file) + "\",\"old_string\":\"missing\",\"new_string\":\"x\"}");
        assertTrue(zero.isError());
        assertTrue(zero.content().contains("未找到"));

        Files.write(file, "x x".getBytes(StandardCharsets.UTF_8));
        Result multiple = registry.execute(ToolContext.fresh(), "edit_file",
                "{\"path\":\"" + escape(file) + "\",\"old_string\":\"x\",\"new_string\":\"y\"}");
        assertTrue(multiple.isError());
        assertTrue(multiple.content().contains("2"));
    }

    @Test
    public void bashEchoReturnsOutput() {
        Registry registry = Registry.defaultRegistry();

        Result result = registry.execute(ToolContext.fresh(), "bash", "{\"command\":\"echo hi\"}");

        assertFalse(result.isError());
        assertTrue(result.content().contains("exit_code: 0"));
        assertTrue(result.content().toLowerCase().contains("hi"));
    }

    @Test
    public void globAndGrepFindFiles() throws Exception {
        Path dir = Files.createTempDirectory("mewcode-search");
        Path file = dir.resolve("src/Main.java");
        Files.createDirectories(file.getParent());
        Files.write(file, "class Main { String token = \"needle\"; }".getBytes(StandardCharsets.UTF_8));
        Registry registry = Registry.defaultRegistry();

        Result glob = registry.execute(ToolContext.fresh(), "glob",
                "{\"path\":\"" + escape(dir) + "\",\"pattern\":\"**/*.java\"}");
        Result grep = registry.execute(ToolContext.fresh(), "grep",
                "{\"path\":\"" + escape(dir) + "\",\"glob\":\"**/*.java\",\"pattern\":\"needle\"}");

        assertFalse(glob.isError());
        assertTrue(glob.content().contains("Main.java"));
        assertFalse(grep.isError());
        assertTrue(grep.content().contains("needle"));
    }

    private String escape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
