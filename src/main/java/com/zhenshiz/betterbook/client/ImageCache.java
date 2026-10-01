package com.zhenshiz.betterbook.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.zhenshiz.betterbook.BetterBook;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import org.jsoup.nodes.Element;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import javax.imageio.ImageIO;

/** 会话级异步图片缓存；纹理的创建与销毁仅发生在客户端线程。 */
public final class ImageCache implements AutoCloseable {
    public static final int MAX_BYTES = 16 * 1024 * 1024, MAX_DIMENSION = 4096;
    private static final HttpClient HTTP =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

    public static final class Entry {
        public ResourceLocation texture;
        public int width = 160, height = 90;
        public String error = "";
        public boolean ready;
    }

    private final Map<String, Entry> entries = new HashMap<>();
    private final List<ResourceLocation> owned = new ArrayList<>();
    private final Runnable changed;
    private boolean closed;

    public ImageCache(Runnable changed) {
        this.changed = changed;
    }

    public Entry get(String src) {
        return entries.computeIfAbsent(src, this::load);
    }

    public float width(Element image, float available) {
        var e = get(image.attr("src"));
        return Math.min(
                available, Math.max(8, DocumentLayout.integer(image.attr("width"), e.width)));
    }

    public float height(Element image, float available) {
        var e = get(image.attr("src"));
        float width = width(image, available);
        int requested = DocumentLayout.integer(image.attr("height"), 0);
        return Math.max(
                8,
                requested > 0
                        ? requested
                                * (width
                                        / Math.max(
                                                width,
                                                DocumentLayout.integer(
                                                        image.attr("width"), (int) width)))
                        : width * e.height / Math.max(1, e.width));
    }

    public void retry(String src) {
        entries.remove(src);
        changed.run();
    }

    private Entry load(String src) {
        var entry = new Entry();
        if (src.isBlank()) {
            entry.error = "gui.betterbook.image_missing";
            entry.ready = true;
            return entry;
        }
        CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                byte[] bytes;
                                if (src.startsWith("data:image/")) {
                                    int comma = src.indexOf(',');
                                    if (comma < 0 || !src.substring(0, comma).endsWith(";base64"))
                                        throw new IOException("Invalid data URL");
                                    if (src.length() > MAX_BYTES * 1.4)
                                        throw new IOException("Image too large");
                                    bytes = Base64.getDecoder().decode(src.substring(comma + 1));
                                } else if (src.startsWith("https://")
                                        || src.startsWith("http://")) {
                                    var response =
                                            HTTP.send(
                                                    HttpRequest.newBuilder(URI.create(src))
                                                            .timeout(Duration.ofSeconds(20))
                                                            .GET()
                                                            .build(),
                                                    HttpResponse.BodyHandlers.ofInputStream());
                                    try (var in = response.body()) {
                                        if (response.statusCode() != 200)
                                            throw new IOException("HTTP " + response.statusCode());
                                        bytes = in.readNBytes(MAX_BYTES + 1);
                                    }
                                } else {
                                    var id = ResourceLocation.parse(src);
                                    try (var in =
                                            Minecraft.getInstance().getResourceManager().open(id)) {
                                        bytes = in.readNBytes(MAX_BYTES + 1);
                                    }
                                }
                                if (bytes.length > MAX_BYTES)
                                    throw new IOException("Image too large");
                                try (var input =
                                        ImageIO.createImageInputStream(
                                                new ByteArrayInputStream(bytes))) {
                                    var readers = ImageIO.getImageReaders(input);
                                    if (!readers.hasNext())
                                        throw new IOException("Unsupported image");
                                    var reader = readers.next();
                                    try {
                                        reader.setInput(input);
                                        int w = reader.getWidth(0), h = reader.getHeight(0);
                                        if (w > MAX_DIMENSION || h > MAX_DIMENSION)
                                            throw new IOException("Image dimensions exceed limit");
                                        var bitmap = reader.read(0);
                                        var out = new ByteArrayOutputStream();
                                        ImageIO.write(bitmap, "png", out);
                                        return NativeImage.read(
                                                new ByteArrayInputStream(out.toByteArray()));
                                    } finally {
                                        reader.dispose();
                                    }
                                }
                            } catch (Exception e) {
                                throw new CompletionException(e);
                            }
                        })
                .whenComplete(
                        (image, error) ->
                                Minecraft.getInstance()
                                        .execute(
                                                () -> {
                                                    if (closed) {
                                                        if (image != null) image.close();
                                                        return;
                                                    }
                                                    if (error != null) {
                                                        entry.error = "gui.betterbook.image_failed";
                                                        BetterBook.LOGGER.debug(
                                                                "Image load failed", error);
                                                    } else {
                                                        entry.width = image.getWidth();
                                                        entry.height = image.getHeight();
                                                        entry.texture =
                                                                Minecraft.getInstance()
                                                                        .getTextureManager()
                                                                        .register(
                                                                                "betterbook",
                                                                                new DynamicTexture(
                                                                                        image));
                                                        owned.add(entry.texture);
                                                    }
                                                    entry.ready = true;
                                                    changed.run();
                                                }));
        return entry;
    }

    @Override
    public void close() {
        closed = true;
        for (var id : owned) Minecraft.getInstance().getTextureManager().release(id);
        owned.clear();
        entries.clear();
    }
}
