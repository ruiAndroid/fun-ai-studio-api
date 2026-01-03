package fun.ai.studio.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * zip 工具：安全解压（防 Zip Slip）
 */
public final class ZipUtils {
    private ZipUtils() {}

    public static void deleteDirectoryRecursively(Path dir) throws IOException {
        if (dir == null || Files.notExists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignore) {
                }
            });
        }
    }

    public static void unzipSafely(InputStream zipStream, Path destDir) throws IOException {
        if (zipStream == null) {
            throw new IllegalArgumentException("zipStream 不能为空");
        }
        if (destDir == null) {
            throw new IllegalArgumentException("destDir 不能为空");
        }
        Files.createDirectories(destDir);
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isBlank()) {
                    continue;
                }
                Path newPath = destDir.resolve(name).normalize();
                if (!newPath.startsWith(destDir)) {
                    throw new IOException("非法zip条目路径: " + name);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(newPath);
                } else {
                    Path parent = newPath.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(zis, newPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }
}


