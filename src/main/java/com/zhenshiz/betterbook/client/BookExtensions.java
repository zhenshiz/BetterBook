package com.zhenshiz.betterbook.client;

import com.lowdragmc.lowdraglib2.registry.AutoRegistry;
import com.zhenshiz.betterbook.api.*;

import net.minecraft.resources.ResourceLocation;

/** 仅在客户端创建注解注册表。 */
public final class BookExtensions {
    private static final RegistryHolder HOLDER = new RegistryHolder();

    private static final class RegistryHolder {
        final AutoRegistry.LDLibRegister<BookExtension, java.util.function.Supplier<BookExtension>>
                registry =
                        AutoRegistry.LDLibRegister.create(
                                ResourceLocation.parse(BookExtension.REGISTRY),
                                BookExtension.class,
                                AutoRegistry::noArgsCreator);
    }

    private BookExtensions() {}

    public static ExtensionContext create() {
        var context = new ExtensionContext();
        for (var extension : HOLDER.registry) {
            ResourceLocation.parse(extension.annotation().name());
            extension.value().get().register(context);
        }
        if (context.schema.nodes().isEmpty())
            throw new IllegalStateException("BetterBook extensions were not registered");
        return context;
    }
}
