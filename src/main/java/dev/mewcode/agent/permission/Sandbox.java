package dev.mewcode.agent.permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 负责项目根目录内的路径沙箱判定。
 */
public final class Sandbox {
    private Sandbox() {
    }

    /**
     * 解析项目根目录。
     */
    public static Path resolveRoot(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        if (Files.exists(normalized)) {
            try {
                return normalized.toRealPath();
            } catch (IOException ignored) {
                return normalized;
            }
        }
        return normalized;
    }

    /**
     * 判断目标路径是否仍然落在项目根目录内。
     */
    public static boolean sandboxOK(Path root, String rawPath) throws IOException {
        Path resolvedRoot = resolveRoot(root);
        Path candidate = rawPath == null || rawPath.trim().isEmpty()
                ? resolvedRoot
                : resolvedRoot.resolve(rawPath).normalize();
        Path checked = evalSymlinksOrAncestor(candidate);
        return checked.equals(resolvedRoot) || checked.startsWith(resolvedRoot);
    }

    /**
     * 若目标不存在，则回退到最近存在的祖先目录做真实路径解析。
     */
    public static Path evalSymlinksOrAncestor(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.exists(absolute)) {
            return tryRealPath(absolute);
        }

        Path cursor = absolute;
        Path suffix = null;
        while (cursor != null && !Files.exists(cursor)) {
            if (suffix == null) {
                suffix = cursor.getFileName();
            } else {
                suffix = cursor.getFileName().resolve(suffix);
            }
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            return absolute;
        }

        Path ancestor = tryRealPath(cursor);
        return suffix == null ? ancestor : ancestor.resolve(suffix).normalize();
    }

    private static Path tryRealPath(Path path) throws IOException {
        try {
            return path.toRealPath();
        } catch (IOException ignored) {
            return path.toAbsolutePath().normalize();
        }
    }
}
