# BetterBook · 更好的书

BetterBook 是面向 Minecraft 整合包作者、模组开发者和服务器管理员的游戏内手册模组。你可以在游戏里制作带有排版、图片、物品、配方、模型和图表的书页，把成书交给玩家，或放在讲台上供大家阅读。多方块结构还能投射到世界中，帮助玩家逐块搭建机器。

当前面向 **Minecraft 1.21.1 / NeoForge**。

仓库内提供 [4 分 50 秒中文全功能介绍片](media/showcase/BetterBook-showcase-zh-CN-1080p.mp4)，使用真实客户端展示阅读、书页组件、世界投影、阶段和制作交付。无字幕版、配音、字幕、封面及可复用制作文件见[视频交付说明](media/showcase/README.md)。

## 主要功能

| 内容 | 能做什么 |
| --- | --- |
| 书页排版 | 标题、段落、引用、列表、待办事项、表格、分隔线，以及粗体、斜体、颜色、上下标和隐藏文字 |
| 交互内容 | 提示框、分步说明、书内页面跳转、关联页面图标、外部链接，以及由服务端处理的点击指令 |
| 阅读布局与导航 | 可选单页或双页布局；可关闭顺序翻页，通过目录和页面链接导航，同时保留返回按钮和页码 |
| 阶段解锁 | 按玩家阶段开放书页；锁定页显示锁和解锁提示，关联页面图标可显示锁或保留原图标 |
| 图片与代码 | 资源包图片、HTTP(S) 图片、剪贴板图片；带高亮、行号和复制功能的代码块 |
| 游戏内容 | 展示物品及其数据组件、可旋转的实体模型、可编辑的配方画布和可选的 JEI 配方视图 |
| 数学与图表 | LaTeX 公式编辑器，以及在书页内显示的 Mermaid 风格图表 |
| 多方块结构 | 在书页中旋转和缩放结构预览；按作者设置将结构投射到世界，显示缺块、错块和完成进度 |
| 多语言 | 多种语言共用页面顺序和阶段要求，每种语言可写独立正文与解锁提示；缺少译文时使用默认语言内容 |

编辑器提供可视化排版、HTML 源码编辑、撤销与重做、页面管理和组件属性面板。阅读器默认使用双页书本界面，可切换为单页，支持翻页、页面跳转和返回；编辑时的预览沿用书页的纸张外观，并忽略阶段要求，方便作者检查全部页面。

### 公式与图表

LaTeX 编辑器提供常用符号、希腊字母、分数、根式、积分和矩阵等模板。可以填写模板参数，也可以直接编辑公式源码；字号和颜色写入 LaTeX 分组语法，并在书页中实时预览。

图表支持常用的流程图、时序图、甘特图、类图、状态图、ER 图、饼图、思维导图和时间线语法。编辑时可以选择模板、修改源码并即时查看结果；阅读时只显示图表，双击可放大。图表由模组在客户端解析和绘制，支持的是 Mermaid 的常用语法子集，并非完整的 Mermaid JavaScript 实现。

## 快速开始

### 体验案例

仓库提供一份**中英双语**的[案例手册](src/main/resources/assets/betterbook/betterbook/books/example.book)，涵盖排版、游戏组件、LaTeX、九类 Mermaid 图表、世界投影和阶段解锁。将它放入游戏或服务端目录的 `ldlib2/assets/betterbook/books/`，同时把[配套工作区结构](src/main/resources/assets/betterbook/betterbook/nbt/betterbook-demo-workshop.nbt)放入 `ldlib2/assets/betterbook/nbt/`，然后执行 `/betterbook open example`。

案例结构已允许投影和玩家调整，需要 9 块橡木木板、1 个工作台、1 个熔炉和 1 个书架。在目录中选择“结构预览”，即可尝试投射；“投影操作”页介绍按键和作者设置。

案例手册还把普通食物和末地食谱放在同一个食物目录中，末地食谱页要求 `demo:entered_end` 阶段。使用下文的 KubeJS 示例，可以在玩家进入末地后自动解锁这些页面；也可用 `/betterbook stage add @s demo:entered_end` 手动体验。关闭书籍的“在页面图标上显示锁定标记”时，目录会保留原来的食物图标，未解锁的食谱仍显示解锁提示并限制访问。

### 制作一本书

1. 在游戏中输入 `/betterbook editor`，通过编辑器的文件菜单新建项目。
2. 在左侧添加页面，在中间撰写正文；通过工具栏插入图片、公式、图表、物品、实体、配方或结构。选中组件后，在右侧修改其设置。
3. 需要其他语言时，添加对应译文并分别编辑正文。
4. 将项目保存为 `.book` 文件，然后使用编辑器的“上传到服务端”发布。上传需要 **2 级权限**。
5. 输入 `/betterbook open <文件名>` 试读，也可以把服务端书籍绑定到空白手册。

如果要修改已经发布的书，可以用 `/betterbook editor <文件名>` 打开服务端文件；再次上传时默认写回该路径。这个命令同样需要 **2 级权限**。

在右侧“书籍信息”中，可设置“跟随 Minecraft 界面尺寸”。开启时，阅读界面跟随玩家的 GUI 比例；关闭时，按窗口大小自动缩放，同一窗口下调整 GUI 比例不会改变书本的显示尺寸。该设置保存在 `.book` 中，对所有语言和阅读预览生效；旧书默认开启。

同一面板还提供以下全局设置；它们保存在 `.book` 中，由所有语言共用，阅读预览也沿用布局和导航设置。旧书未保存这些字段时使用表中的默认值。

| 设置 | 字段与默认值 | 效果 |
| --- | --- | --- |
| 单页布局 | `singlePage = false` | 开启后每次显示一张竖版书页，正文和组件采用更紧凑的尺寸；默认并排显示两页 |
| 允许翻页 | `allowPageTurning = true` | 关闭后隐藏上一页、下一页按钮，并禁用滚轮和快捷键顺序翻页；页面链接、返回按钮和页码仍保留 |
| 在页面图标上显示锁定标记 | `lockedIcons = true` | 开启后，指向未解锁页面的关联图标显示为锁；关闭后保留作者设置的原图标，例如食物图标，阶段要求仍生效 |

### 交给玩家阅读

创造模式的 BetterBook 标签页提供空白手册、成书和结构选区工具。玩家右键空白手册，可以从服务端书籍列表中选择要绑定的文件；成书右键后打开阅读器。手册保存的是书籍引用，打开时读取服务端的最新版本。

绑定完成的成书可以放到原版空讲台上供其他玩家阅读。每位玩家独立翻页；取下或破坏讲台时，书籍仍按原版讲台的方式处理。

## 阅读导航与阶段解锁

如果希望玩家通过目录选择内容，可在“书籍信息”中关闭“允许翻页”，并在目录页插入页面链接或关联页面图标。开启“单页布局”后，目录和目标页每次各占一个完整书页；返回按钮可回到之前访问的页面，页码继续显示当前位置与总页数。

在编辑器左侧页面列表中，右键目标页面并选择“页面设置”。两个字段都使用 LDLib2 自动生成的列表表单，可添加、删除和重排条目；使用说明放在字段旁的悬停 tips 中：

- **所需阶段**：`List<String>`，玩家必须拥有列表中的**全部阶段**才能阅读。例如同时填写 `demo:entered_end` 和 `demo:found_archive`，仅完成其中一个不会解锁。空列表不限制阅读，空白条目忽略，重复名称合并；要求由该页面的所有语言版本共用。
- **解锁提示**：`List<String>`，每个条目显示为一行，空条目可用于分隔段落。按当前编辑语言分别保存，例如两行“进入末地”“找到档案馆”。缺少译文或译文提示全部为空白时，使用默认语言的完整提示列表；没有作者提示时显示默认解锁说明。

旧 `.book` 仍可打开：单个阶段自动转为一项列表，旧提示文本按换行拆分；再次保存时使用新的列表格式。

没有所需阶段的玩家翻到该页时，只会看到锁图标和解锁提示占位，正文不会显示。悬停指向锁定页的页面链接和关联图标时，会在鼠标旁显示与物品提示框相同的解锁说明，提示框仅显示文字；移开鼠标后消失，点击不会进入正文。关闭 `lockedIcons` 只改变目录图标的外观。作者编辑和阅读预览忽略阶段要求，可检查全部正文；玩家通过 `/betterbook open` 或成书打开阅读器时，按自己的阶段判断访问权限。

阶段名会去掉首尾空白并转为小写，规范化后必须符合 `[a-z0-9_][a-z0-9_./:-]{0,127}`，长度为 **1–128 个字符**。可使用 `demo:entered_end`、`pack/chapter_1` 等名称；名称中间不能有空格。建议用整合包或模组前缀区分用途，因为同一玩家的阶段名称由不同书籍共用。

BetterBook 的玩家阶段使用主世界的 **SavedData**，按 **UUID** 独立记录，文件位于存档目录的 `data/betterbook_player_stages.dat`，没有使用玩家数据附件。重登、死亡、切换维度和重启后保留，不会授予其他玩家，也不会带到其他世界或服务器。阶段与 `.book` 内容分开保存，因此授予一个阶段即可满足该玩家在不同书籍中要求同名阶段的条件。这是 BetterBook 自己的阶段数据；KubeJS 的 `player.stages` 不会自动授予 BetterBook 阶段。

## 多方块结构与世界投影

BetterBook 可以读取原版结构 `.nbt` 和 Forgematica `.litematic`。作者可将文件放入服务端的结构目录，也可以用创造栏中的结构选区工具左键、右键选择两个角点，然后执行：

```mcfunction
/betterbook structure export workshop
```

生成的文件位于 `ldlib2/assets/betterbook/nbt/workshop.litematic`。在编辑器里插入“多方块结构”组件，从服务端文件列表选择它，即可在书页中预览。选区导出需要 **2 级权限**。

作者还可以在该组件的设置中开启：

- **允许投射到世界**：阅读书籍时显示“投射到世界”按钮。默认关闭。
- **允许玩家调整投影**：玩家可以旋转投影，或在放下投影后重新选点。默认关闭；关闭后仍允许首次选定位置。

玩家点击按钮后，对准世界中的方块表面选择结构的底角位置，右键确认，然后正常放置所需方块。半透明模型表示待放置的方块；已放正确的方块会从投影中消失；方块种类或放置朝向不对时，对应位置出现红色轮廓。左上角会显示完成数量、错块数量和未加载数量。全部补齐后，投影自动移除。

| 默认按键 | 操作 |
| --- | --- |
| `J` | 顺时针旋转投影，需作者允许调整 |
| `G` | 重新选择位置，需作者允许调整 |
| `H` | 取消投影 |

这些按键可以在游戏的按键设置中修改。投影只提供客户端搭建指引；放置方块仍使用正常的 Minecraft 交互，并遵守服务器的物品消耗与权限规则。完成进度比较方块类型和常见放置属性，例如朝向、轴向和上下半；机器运行状态、容器内容和实体不计入搭建进度。世界投影最多处理 **16,384 个非空气方块**，且一次只显示一个结构。

## 安装与依赖

| 组件 | 要求 |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.248 或以上的兼容版本 |
| Java | 21 |
| LDLib2 | 2.2.40 或以上，需单独安装 |
| JEI | 可选；安装后可在配方组件中使用 JEI 的配方展示 |
| KubeJS | 可选；用于通过服务端脚本授予和管理 BetterBook 阶段，命令和 Java API 不要求安装它 |

将 BetterBook 与 LDLib2 安装在客户端和服务端。ViScriptLib、公式排版、HTML 解析和代码编辑所需的库已随 BetterBook 发布 JAR 内嵌。制作 `.litematic` 文件时可以使用 Forgematica；阅读、预览和投射现有结构文件不要求玩家安装它。

## 服务端文件与命令

服务端运行目录中的资源位置：

```text
ldlib2/assets/betterbook/
├── books/
│   ├── guide.book
│   └── machines/press.book
└── nbt/
    ├── workshop.litematic
    └── example.nbt
```

单人游戏使用当前游戏实例的目录；专用服务器使用服务器的游戏目录。命令中的书籍名可使用子目录，并可省略 `.book` 后缀，例如 `/betterbook open machines/press`。

| 命令 | 用途 | 权限 |
| --- | --- | --- |
| `/betterbook editor` | 打开编辑器 | 普通玩家 |
| `/betterbook editor <文件名>` | 编辑服务端书籍 | 2 级 |
| `/betterbook open <文件名>` | 阅读服务端书籍 | 普通玩家 |
| `/betterbook structure export <名称>` | 导出已选区域为 `.litematic` | 2 级 |
| `/betterbook structure clear` | 清除当前结构选区 | 2 级 |
| `/betterbook stage add <players> <stage>` | 为目标玩家授予阶段 | 2 级 |
| `/betterbook stage remove <players> <stage>` | 撤销目标玩家的指定阶段 | 2 级 |
| `/betterbook stage list <player>` | 列出一名玩家的阶段 | 2 级 |
| `/betterbook stage clear <players>` | 清空目标玩家的 BetterBook 阶段 | 2 级 |

阶段命令中的 `<players>` 支持在线玩家名或玩家选择器，例如 `@s`、`@a`；`list` 的 `<player>` 只接受一个玩家。`remove` 的阶段补全实时读取目标玩家当前拥有的阶段；选择多名玩家时取其阶段的并集，移除后该阶段会从相应补全结果中消失。名称使用前述规范化规则。例如：

```mcfunction
/betterbook stage add @s demo:entered_end
/betterbook stage list @s
/betterbook stage remove @s demo:entered_end
/betterbook stage clear @s
```

书中的点击指令由服务端读取已发布的书籍并执行，权限等级固定为 **2 级**。只有可信任的作者应获得编辑和上传服务端书籍的权限。

`.book` 是保存 HTML 正文与多语言信息的 NBT 书籍文件；结构 `.nbt` 与 `.litematic` 单独存放。开发者可通过 [BookExtension 接口](src/main/java/com/zhenshiz/betterbook/api/BookExtension.java)扩展书页节点和编辑能力。

## Java 与 KubeJS 阶段 API

Java API 为 [`com.zhenshiz.betterbook.api.BetterBookStages`](src/main/java/com/zhenshiz/betterbook/api/BetterBookStages.java)。以下方法均为静态方法，必须在服务端主线程调用，`player` 参数为 `net.minecraft.server.level.ServerPlayer`；需要阶段名的方法使用 `String stage`，并执行相同的名称规范化和验证。

| 方法 | 返回值与用途 |
| --- | --- |
| `has(ServerPlayer player, String stage)` | `boolean`：查询玩家是否拥有阶段 |
| `add(ServerPlayer player, String stage)` | `boolean`：新增时为 `true`，已拥有时为 `false` |
| `remove(ServerPlayer player, String stage)` | `boolean`：移除时为 `true`，原本没有时为 `false` |
| `list(ServerPlayer player)` | `List<String>`：按名称排序的不可变阶段快照 |
| `clear(ServerPlayer player)` | `int`：实际移除的阶段数量 |

阶段变化会同步给对应玩家。安装 KubeJS 后，LDLib2 的 `@KJSBindings` 会把这个类注册为全局 `BetterBookStages`，可直接调用 `BetterBookStages.add(player, 'demo:entered_end')`。下面的完整示例通过 `Java.loadClass` 取得同一个类，并使用别名 `BBStages`。

将脚本保存到游戏实例或服务端目录的 `kubejs/server_scripts/betterbook_stages.js`，然后重新加载世界或重启服务器：

```javascript
const BBStages = Java.loadClass('com.zhenshiz.betterbook.api.BetterBookStages');
const END_STAGE = 'demo:entered_end';

function grantBetterBookEndStage(event) {
    const player = event.getPlayer();
    if (BBStages.has(player, END_STAGE)) return;

    const dimension = String(event.getLevel().getDimension());
    if (dimension === 'minecraft:the_end') {
        BBStages.add(player, END_STAGE);
    }
}

// 登录时已在末地的玩家也会获得阶段。
PlayerEvents.loggedIn(grantBetterBookEndStage);
// 进入末地后，在下一次玩家 tick 授予；已获得时不重复写入。
PlayerEvents.tick(grantBetterBookEndStage);
```

示例按本项目已安装的 **KubeJS NeoForge `2101.7.2-build.368`** 源码核对：`PlayerEvents.loggedIn` 和服务端的 `PlayerEvents.tick` 提供服务端玩家；`event.getLevel()` 返回所在世界，KubeJS 的 `getDimension()` 返回维度 ID，因此可以与 `minecraft:the_end` 比较。项目已配置该版本的 KubeJS 开发运行时；整合包使用脚本时需自行安装 KubeJS。

进入末地后，案例手册中要求 `demo:entered_end` 的食谱页会解锁，离开末地后仍可阅读。可用 `/betterbook stage list @s` 检查结果；测试撤销或清空时应先离开末地，否则这个示例会再次授予阶段。作者阅读预览始终可以查看这些食谱。

## 从源码构建

使用 Java 21：

```bash
./gradlew build
```

发布 JAR 输出到 `build/libs/`。本项目还提供 `runClient`、`runGameTestServer` 和 `test` 等开发任务；本地造图模组的加载方式见 [libs/README.md](libs/README.md)。

## 许可证

BetterBook 采用 [GNU GPL 3.0](LICENSE) 许可证。随 JAR 内嵌的第三方库及字体保留各自的许可证。
