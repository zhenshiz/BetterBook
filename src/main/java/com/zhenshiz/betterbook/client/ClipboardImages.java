package com.zhenshiz.betterbook.client;

import java.io.IOException;
import java.nio.file.*;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/** 按粘贴请求读取图片；普通文本继续使用 Minecraft 自身的剪贴板处理。 */
final class ClipboardImages {
    private ClipboardImages() {}

    static String read() throws IOException, InterruptedException {
        Path directory = Files.createTempDirectory("betterbook-clipboard-");
        String resource = ClipboardImageReader.class.getName().replace('.', '/') + ".class";
        Path target = directory.resolve(resource),
                output = directory.resolve("image.png"),
                error = directory.resolve("error.txt");
        Process process = null;
        try {
            Files.createDirectories(target.getParent());
            try (var input = ClipboardImageReader.class.getResourceAsStream("/" + resource)) {
                if (input == null) throw new IOException("Missing clipboard reader");
                Files.copy(input, target);
            }
            String executable =
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            process =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", executable)
                                            .toString(),
                                    "-Xmx256m",
                                    "-Djava.awt.headless=false",
                                    "-cp",
                                    directory.toString(),
                                    ClipboardImageReader.class.getName())
                            .redirectOutput(output.toFile())
                            .redirectError(error.toFile())
                            .start();
            if (!process.waitFor(5, TimeUnit.SECONDS))
                throw new IOException("Clipboard image read timed out");
            if (process.exitValue() != 0)
                throw new IOException("Clipboard image read failed: " + Files.readString(error));
            if (Files.size(output) == 0) return null;
            if (Files.size(output) > 16L * 1024 * 1024)
                throw new IOException("Clipboard image is too large");
            return "data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(Files.readAllBytes(output));
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
            try (var files = Files.walk(directory)) {
                for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(file);
            }
        }
    }
}
