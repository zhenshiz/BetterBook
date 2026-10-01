package com.zhenshiz.betterbook.data;

import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;

import lombok.NoArgsConstructor;

/** 使用 LDLib2 持久化编解码传输压缩结构文件片段。 */
@NoArgsConstructor
public final class StructureFileChunk implements IPersistedSerializable {
    @Persisted public String transfer = "", name = "";
    @Persisted public int index, total;
    @Persisted public byte[] data = new byte[0];

    /**
     * @param transfer 传输标识
     * @param name 文件名
     * @param index 序号
     * @param total 总片数
     * @param data 压缩字节
     */
    public StructureFileChunk(String transfer, String name, int index, int total, byte[] data) {
        this.transfer = transfer;
        this.name = name;
        this.index = index;
        this.total = total;
        this.data = data;
    }
}
