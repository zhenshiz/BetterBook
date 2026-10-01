package com.zhenshiz.betterbook.api;

import com.lowdragmc.lowdraglib2.registry.ILDLRegister;

import java.util.function.Supplier;

/** Java 扩展注册契约；每个编辑或阅读会话获得独立扩展实例。 */
public interface BookExtension extends ILDLRegister<BookExtension, Supplier<BookExtension>> {
    String REGISTRY = "betterbook:extensions";

    /**
     * 向当前会话贡献节点、命令和视图。
     *
     * @param context 当前会话独享的注册上下文
     */
    void register(ExtensionContext context);
}
