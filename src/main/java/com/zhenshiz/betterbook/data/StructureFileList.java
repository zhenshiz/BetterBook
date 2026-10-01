package com.zhenshiz.betterbook.data;

import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;

import java.util.ArrayList;
import java.util.List;

/** 服务端结构目录的补全结果，由 LDLib2 编解码。 */
public final class StructureFileList implements IPersistedSerializable {
    @Persisted public String request = "", error = "";
    @Persisted public List<String> files = new ArrayList<>();
}
