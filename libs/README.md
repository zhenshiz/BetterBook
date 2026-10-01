# 本地模组依赖

- `runtime/`：功能性模组，始终加入编译类路径和运行时类路径。
- `authoring/`：造图时使用的模组，只加入运行时类路径，默认启用。
- 为了兼容原有用法，直接放在 `libs/` 下的 JAR 与 `runtime/` 中的 JAR 采用相同方式加载。

两个分类目录都会递归扫描，因此可以继续创建任意层级的子目录。

使用以下命令可以在本次 Gradle 运行中禁用所有造图模组：

```shell
./gradlew runClient -Pinclude_authoring_mods=false
```

本地模组依赖不会嵌入当前项目生成的 JAR。发布时需要单独分发必需的功能性模组，并在 `neoforge.mods.toml` 中声明必需前置。

## 当前可选造图依赖

- Forgematica `0.4.2+mc1.21.1`，Modrinth 版本 ID `71jxaAwz`。
- MaFgLib `0.4.3+mc1.21.1`，Modrinth 版本 ID `CgDQ0u0Q`（Forgematica 的前置）。
- 两者保存在 `libs/authoring/`，用于客户端结构互读验证；不嵌入 BetterBook JAR，也不是 BetterBook 的必需前置。
- 独立服务端测试使用 `-Pinclude_authoring_mods=false`。
- 来源：[Forgematica](https://modrinth.com/mod/forgematica/version/71jxaAwz)、[MaFgLib](https://modrinth.com/mod/mafglib/version/CgDQ0u0Q)。
