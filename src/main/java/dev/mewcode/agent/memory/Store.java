package dev.mewcode.agent.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 负责某一级 memory 目录的索引读取和笔记增删改。
 */
public final class Store {
    private final Path dir;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 使用目标目录创建记忆存储。
     */
    public Store(Path dir) {
        this.dir = dir;
    }

    /**
     * 返回 MEMORY.md 的完整文本，不存在时返回空串。
     */
    public String loadIndex() throws IOException {
        try {
            return Files.readString(indexFile(), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return "";
        }
    }

    /**
     * 执行一组 create/update/delete 动作。
     */
    public void apply(List<UpdateAction> actions) throws IOException {
        if (actions == null || actions.isEmpty()) {
            return;
        }
        lock.lock();
        try {
            ensureDir();
            List<String> indexLines = readIndexLines();
            for (UpdateAction action : actions) {
                if (action == null || action.slug() == null || action.slug().isBlank()) {
                    continue;
                }
                if ("delete".equalsIgnoreCase(action.action())) {
                    deleteNote(action, indexLines);
                } else {
                    upsertNote(action, indexLines);
                }
            }
            Files.write(indexFile(), indexLines, StandardCharsets.UTF_8);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回当前 memory 目录路径。
     */
    public Path dir() {
        return dir;
    }

    /**
     * 确保目录存在。
     */
    private void ensureDir() throws IOException {
        Files.createDirectories(dir);
    }

    /**
     * 创建或更新一条笔记，并同步更新索引行。
     */
    private void upsertNote(UpdateAction action, List<String> indexLines) throws IOException {
        String fileName = fileNameOf(action);
        Path noteFile = dir.resolve(fileName);
        String title = blankTo(action.title(), action.slug());
        String content = blankTo(action.content(), "");
        String body = "---\n"
                + "level: " + blankTo(action.level(), "project") + "\n"
                + "type: " + blankTo(action.type(), "project_fact") + "\n"
                + "slug: " + action.slug() + "\n"
                + "title: " + title + "\n"
                + "---\n\n"
                + content.trim() + "\n";
        Files.writeString(noteFile, body, StandardCharsets.UTF_8);
        replaceIndexLine(indexLines, action.slug(), "- [" + title + "](" + fileName + ") | type="
                + blankTo(action.type(), "project_fact"));
    }

    /**
     * 删除笔记文件并移除对应索引。
     */
    private void deleteNote(UpdateAction action, List<String> indexLines) throws IOException {
        Files.deleteIfExists(dir.resolve(fileNameOf(action)));
        Iterator<String> iterator = indexLines.iterator();
        while (iterator.hasNext()) {
            String line = iterator.next();
            if (line.contains("(" + fileNameOf(action) + ")")) {
                iterator.remove();
            }
        }
    }

    /**
     * 读取索引文件为可修改行列表。
     */
    private List<String> readIndexLines() throws IOException {
        try {
            return new ArrayList<String>(Files.readAllLines(indexFile(), StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            List<String> lines = new ArrayList<String>();
            lines.add("# MEMORY");
            lines.add("");
            return lines;
        }
    }

    /**
     * 用 slug 定位并替换索引行，不存在时追加。
     */
    private void replaceIndexLine(List<String> indexLines, String slug, String newLine) {
        String expected = safeSlug(slug);
        for (int i = 0; i < indexLines.size(); i++) {
            if (indexLines.get(i).contains(expected + ".md)")) {
                indexLines.set(i, newLine);
                return;
            }
        }
        if (!indexLines.isEmpty() && !indexLines.get(indexLines.size() - 1).isBlank()) {
            indexLines.add("");
        }
        indexLines.add(newLine);
    }

    /**
     * 生成笔记文件名。
     */
    private String fileNameOf(UpdateAction action) {
        if (action.filename() != null && !action.filename().isBlank()) {
            return action.filename();
        }
        return blankTo(action.type(), "project_fact") + "_" + safeSlug(action.slug()) + ".md";
    }

    /**
     * 清洗 slug，避免产生不安全文件名。
     */
    private String safeSlug(String slug) {
        return slug == null ? "note" : slug.trim().replaceAll("[^a-zA-Z0-9._-]+", "-");
    }

    /**
     * 返回索引文件路径。
     */
    private Path indexFile() {
        return dir.resolve("MEMORY.md");
    }

    /**
     * 在字符串为空时使用默认值。
     */
    private String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
