package com.inventory.export;

import java.io.InputStream;
import java.io.IOException;

/** 已经打开的文件句柄；Controller取得后必须关闭，不序列化为JSON。 */
public record InventoryExportDownloadFile(InputStream input, String fileName, long fileSize) implements AutoCloseable {
    @Override public void close() throws IOException { input.close(); }
}
