package com.zhenshiz.betterbook.api;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.zhenshiz.betterbook.core.*;

import org.jsoup.nodes.Element;

import java.util.*;
import java.util.function.*;

/** 会话级扩展贡献表；菜单只引用命令 ID，不直接改写文档。 */
public final class ExtensionContext {
    /**
     * 命令描述。
     *
     * @param id 命名空间命令标识
     * @param label 菜单显示的翻译键
     * @param enabled 不改变内容的可执行条件
     * @param execute 在事务中运行的内容修改操作
     */
    public record Command(
            String id,
            String label,
            Predicate<BookSession> enabled,
            Consumer<BookSession> execute) {}

    /**
     * 编辑器键盘绑定。
     *
     * @param key GLFW 键码
     * @param modifier 是否要求 Ctrl 或 Cmd
     * @param shift 是否要求 Shift
     * @param command 已注册的命令标识
     */
    public record Shortcut(int key, boolean modifier, boolean shift, String command) {}

    /**
     * 自定义原子节点视图的创建参数。
     *
     * @param element 当前节点快照
     * @param editable 是否处于编辑器
     * @param update 通过事务提交节点属性与内容替换的回调
     */
    public record NodeViewContext(Element element, boolean editable, Consumer<Element> update) {}

    public final Schema schema = new Schema();
    public final Map<String, Command> commands = new LinkedHashMap<>();
    public final List<Shortcut> shortcuts = new ArrayList<>();
    public final List<Predicate<BookSession>> inputRules = new ArrayList<>();

    /** 插件标记的原生文字样式转换，按标记嵌套顺序组合。 */
    public final Map<
                    String,
                    BiFunction<
                            RichDocument.Mark,
                            net.minecraft.network.chat.Style,
                            net.minecraft.network.chat.Style>>
            markStyles = new LinkedHashMap<>();

    public final Map<String, Function<NodeViewContext, UIElement>> nodeViews =
            new LinkedHashMap<>();
    public final Map<String, BiConsumer<BookSession, UIElement>> inspectors = new LinkedHashMap<>();
    public final List<String> insertMenu = new ArrayList<>();

    /**
     * 注册命令并可将其加入通用插入菜单。
     *
     * @param command 当前会话使用的命令描述
     * @param menu 是否加入插入菜单
     * @throws IllegalArgumentException 标识无效或重复时抛出
     */
    public void command(Command command, boolean menu) {
        Schema.requireId(command.id());
        if (commands.putIfAbsent(command.id(), command) != null)
            throw new IllegalArgumentException("Duplicate command: " + command.id());
        if (menu) insertMenu.add(command.id());
    }

    /**
     * 执行可用命令，将嵌套修改合为一个可撤销事务。
     *
     * @param id 命令标识
     * @param session 目标书籍会话
     * @return 命令存在且满足可执行条件时为 true
     */
    public boolean execute(String id, BookSession session) {
        var c = commands.get(id);
        if (c == null || !c.enabled().test(session)) return false;
        session.transact(() -> c.execute().accept(session));
        return true;
    }

    /**
     * 按注册顺序应用输入规则，首个返回 true 的规则结束匹配。
     *
     * @param session 已提交当前输入的编辑会话
     */
    public void applyInputRules(BookSession session) {
        for (var rule : inputRules) if (rule.test(session)) break;
    }
}
