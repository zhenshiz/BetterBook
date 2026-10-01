package com.zhenshiz.betterbook.core;

import java.util.List;

/** 模板用于插入和回归验证，所有示例采用同一解析/绘制入口。 */
public final class MermaidTemplates {
    private MermaidTemplates() {}

    public record Template(String id, String source) {}

    public static final List<Template> ALL =
            List.of(
                    new Template("flowchart", MermaidNode.EXAMPLE),
                    new Template(
                            "sequence",
                            "sequenceDiagram\n"
                                + "    autonumber\n"
                                + "    actor P as 玩家\n"
                                + "    participant S as 服务端\n"
                                + "    P->>+S: 请求打开书籍\n"
                                + "    alt 验证成功\n"
                                + "        S-->>P: 返回书籍内容\n"
                                + "    else 验证失败\n"
                                + "        S-->>P: 显示错误\n"
                                + "    end\n"
                                + "    deactivate S\n"
                                + "    Note over P,S: 完成读取"),
                    new Template(
                            "gantt",
                            "gantt\n"
                                + "    title 项目进度\n"
                                + "    dateFormat YYYY-MM-DD\n"
                                + "    axisFormat %m-%d\n"
                                + "    section 准备\n"
                                + "    需求整理 :done, a, 2026-10-01, 3d\n"
                                + "    设计 :active, b, after a, 4d\n"
                                + "    section 实现\n"
                                + "    开发 :crit, c, after b, 5d\n"
                                + "    验收 :d, after c, 2d"),
                    new Template(
                            "class",
                            "classDiagram\n"
                                + "    class Book {\n"
                                + "        +String title\n"
                                + "        +open()\n"
                                + "    }\n"
                                + "    class Page {\n"
                                + "        +String content\n"
                                + "        +render()\n"
                                + "    }\n"
                                + "    Book \"1\" *-- \"many\" Page : 包含"),
                    new Template(
                            "state",
                            "stateDiagram-v2\n"
                                + "    [*] --> Idle\n"
                                + "    state \"空闲\" as Idle\n"
                                + "    state \"阅读\" as Reading\n"
                                + "    Idle --> Reading : 打开\n"
                                + "    Reading --> Idle : 关闭\n"
                                + "    Idle --> [*]"),
                    new Template(
                            "er",
                            "erDiagram\n"
                                + "    PLAYER ||--o{ BOOK : 持有\n"
                                + "    BOOK ||--|{ PAGE : 包含\n"
                                + "    PLAYER {\n"
                                + "        string id PK\n"
                                + "        string name\n"
                                + "    }\n"
                                + "    BOOK {\n"
                                + "        string id PK\n"
                                + "        string owner FK\n"
                                + "    }\n"
                                + "    PAGE {\n"
                                + "        int index PK\n"
                                + "        string content\n"
                                + "    }"),
                    new Template(
                            "pie",
                            "pie showData\n"
                                + "    title 书籍内容\n"
                                + "    \"教程\" : 50\n"
                                + "    \"配方\" : 30\n"
                                + "    \"其他\" : 20"),
                    new Template(
                            "mindmap",
                            "mindmap\n"
                                + "    root((手册))\n"
                                + "        入门\n"
                                + "            操作\n"
                                + "            设置\n"
                                + "        内容\n"
                                + "            配方\n"
                                + "            结构\n"
                                + "        帮助"),
                    new Template(
                            "timeline",
                            "timeline\n"
                                + "    title 开发计划\n"
                                + "    section 前期\n"
                                + "    第一周 : 需求 : 设计\n"
                                + "    第二周 : 原型\n"
                                + "    section 后期\n"
                                + "    第三周 : 开发\n"
                                + "    第四周 : 验收 : 发布"));
}
