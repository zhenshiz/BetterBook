package com.zhenshiz.betterbook;

import com.mojang.logging.LogUtils;
import com.viscript_lib.annotation.ViScriptRegisterAccessors;
import com.viscript_lib.event.RegisterAccessorEvent;
import com.zhenshiz.betterbook.data.*;

import net.neoforged.fml.common.Mod;

import org.slf4j.Logger;

/** 注册 BetterBook 模组；编辑器和阅读器由客户端事件独立初始化。 */
@Mod(BetterBook.ID)
public final class BetterBook {
    public static final String ID = "betterbook";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 在 VSL 的早期注册阶段提供数据访问器，先于 LDLib2 RPC 元信息解析。
     *
     * @param event VSL 访问器注册上下文
     */
    @ViScriptRegisterAccessors
    public static void registerAccessors(RegisterAccessorEvent event) {
        event.register(BookData.PageData.class, BookData.PageData::new);
        event.register(BookData.LanguageData.class, BookData.LanguageData::new);
        event.register(BookData.class, BookData::new);
        event.register(BookBinding.class, BookBinding::new);
        event.register(BookRecipe.Part.class, BookRecipe.Part::new);
        event.register(BookRecipe.class, BookRecipe::new);
        event.register(BookRelatedPages.Entry.class, BookRelatedPages.Entry::new);
        event.register(BookRelatedPages.class, BookRelatedPages::new);
        event.register(StructureFileList.class, StructureFileList::new);
        event.register(StructureFileChunk.class, StructureFileChunk::new);
    }

    public BetterBook(net.neoforged.bus.api.IEventBus bus) {
        com.zhenshiz.betterbook.data.BookItems.register(bus);
        BookMenus.register();
    }
}
