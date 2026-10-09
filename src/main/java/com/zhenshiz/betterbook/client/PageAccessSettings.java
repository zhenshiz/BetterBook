package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.zhenshiz.betterbook.core.BookSession;

import java.util.ArrayList;
import java.util.List;

/** 页面设置的独立编辑草稿，应用前不修改书籍或撤销历史。 */
final class PageAccessSettings implements IConfigurable {
    @Configurable(name = "gui.betterbook.page_stage", collapse = false,
            tips = {"gui.betterbook.page_stage_hint", "gui.betterbook.preview_stage_hint"})
    public List<String> stages = new ArrayList<>();

    @Configurable(name = "gui.betterbook.page_unlock_hint_label", collapse = false,
            tips = "gui.betterbook.page_unlock_hint_help")
    public List<String> unlockHint = new ArrayList<>();

    PageAccessSettings(BookSession session) {
        stages.addAll(session.book().requiredStages.getOrDefault(session.page().id(), List.of()));
        unlockHint.addAll(session.page().unlockHint());
    }
}
