package com.zhenshiz.betterbook.core;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 一段文字绑定的服务端指令。
 *
 * @param id 绑定的稳定标识；修改指令时生成新标识
 * @param command 不含开头斜杠的单条指令
 * @param permission 旧版权限字段；统一规范为固定的 2 级权限
 * @param underline 是否显示绑定文字的下划线
 */
public record TextCommand(String id, String command, int permission, boolean underline) {
    public static final String MARK = "betterbook:command";
    public static final int EXECUTION_PERMISSION = 2;

    /**
     * 创建默认显示下划线的绑定。
     *
     * @param id 绑定标识
     * @param command 单条指令
     * @param permission 旧版权限字段；统一规范为固定的 2 级权限
     * @throws IllegalArgumentException 标识或指令不合法时抛出
     */
    public TextCommand(String id, String command, int permission) {
        this(id, command, permission, true);
    }

    public TextCommand {
        permission = EXECUTION_PERMISSION;
        UUID.fromString(id);
        command = command.strip();
        if (command.startsWith("/")) command = command.substring(1).strip();
        if (command.isEmpty()
                || command.length() > 2048
                || command.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid text command");
    }

    /**
     * 创建新的文字绑定。
     *
     * @param command 单条指令，允许开头斜杠
     * @return 使用固定 2 级权限的新绑定
     * @throws IllegalArgumentException 指令为空、过长或含控制字符时抛出
     */
    public static TextCommand create(String command) {
        return new TextCommand(UUID.randomUUID().toString(), command, EXECUTION_PERMISSION);
    }

    /**
     * 读取 HTML 标记中的绑定；忽略旧版权限属性，始终使用 2 级权限。
     *
     * @param attributes 标记属性
     * @return 有效绑定；属性不合法时为空
     */
    public static Optional<TextCommand> read(Map<String, String> attributes) {
        try {
            return Optional.of(
                    new TextCommand(
                            attributes.getOrDefault("data-command-id", ""),
                            attributes.getOrDefault("data-command", ""),
                            EXECUTION_PERMISSION,
                            !attributes
                                    .getOrDefault("data-underline", "true")
                                    .equalsIgnoreCase("false")));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * 生成可保存的行内标记。
     *
     * @return 带指令、权限和稳定标识的 span 标记
     */
    public RichDocument.Mark mark() {
        return new RichDocument.Mark(
                MARK,
                "span",
                Map.of(
                        "data-type",
                        "command",
                        "data-command-id",
                        id,
                        "data-command",
                        command,
                        "data-permission",
                        Integer.toString(permission),
                        "data-underline",
                        Boolean.toString(underline)));
    }
}
