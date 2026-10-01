package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.gui.ui.utils.UIElementProvider;
import com.lowdragmc.lowdraglib2.utils.search.IResultHandler;
import com.viscript_lib.gui.components.search.JsonFileSearchBox;
import com.zhenshiz.betterbook.data.ServerStructureFiles;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 扩展 VSL 文件补全框的搜索来源，沿用原生弹层及选择交互。 */
public final class StructureFileSearchBox extends JsonFileSearchBox {
    private volatile Map<ResourceLocation, String> files = Map.of();
    private volatile Set<ResourceLocation> candidatesFromServer = Set.of();
    private String query = "";
    private int generation;

    public StructureFileSearchBox() {
        super("nbt", () -> null);
        setCandidateUIProvider(
                UIElementProvider.text(
                        id ->
                                Component.literal(
                                        id == null ? "" : files.getOrDefault(id, id.toString()))));
        setSearchUI(
                new ISearchUI<ResourceLocation>() {
                    public String resultText(ResourceLocation value) {
                        return value == null ? "" : files.getOrDefault(value, value.toString());
                    }

                    public void onResultSelected(ResourceLocation value) {}

                    public void search(String word, IResultHandler<ResourceLocation> handler) {
                        StructureFileSearchBox.this.search(word, handler);
                    }
                });
        getSearchStyle().closeAfterSelect(true);
    }

    @Override
    public void show() {
        if (isOpen()) return;
        int request = ++generation;
        candidatesFromServer = Set.of();
        super.show();
        StructureFiles.list()
                .whenCompleteAsync(
                        (result, error) -> {
                            if (request != generation || !isOpen()) return;
                            if (error != null) {
                                getStyle()
                                        .tooltips(
                                                Component.translatable(
                                                        "gui.betterbook.structure_failed",
                                                        error.getMessage()));
                                return;
                            }
                            var map = new LinkedHashMap<ResourceLocation, String>();
                            for (String file : result) map.put(key(file), file);
                            candidatesFromServer = Set.copyOf(map.keySet());
                            String previous = reference();
                            if (!previous.isEmpty()) map.put(key(previous), previous);
                            files = Map.copyOf(map);
                            onSearchWordChanged(query);
                        },
                        Minecraft.getInstance());
    }

    @Override
    protected void onSearchWordChanged(String word) {
        query = word;
        super.onSearchWordChanged(word);
    }

    @Override
    protected void search(String word, IResultHandler<ResourceLocation> handler) {
        String query = word.toLowerCase(Locale.ROOT);
        files.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .takeWhile(e -> !Thread.currentThread().isInterrupted())
                .filter(e -> candidatesFromServer.contains(e.getKey()))
                .filter(e -> e.getValue().toLowerCase(Locale.ROOT).contains(query))
                .forEach(e -> handler.acceptResult(e.getKey()));
    }

    /**
     * @return 当前选中的完整相对引用
     */
    public String reference() {
        return getValue() == null ? "" : files.getOrDefault(getValue(), "");
    }

    /**
     * @param reference 文档中保存的结构引用
     */
    public void reference(String reference) {
        reference = ServerStructureFiles.canonical(reference);
        if (reference.isEmpty()) {
            setValue(null, false);
            return;
        }
        var map = new HashMap<>(files);
        var id = key(reference);
        map.put(id, reference);
        files = Map.copyOf(map);
        setValue(id, false);
    }

    private static ResourceLocation key(String reference) {
        return ResourceLocation.fromNamespaceAndPath(
                "betterbook", HexFormat.of().formatHex(reference.getBytes(StandardCharsets.UTF_8)));
    }
}
