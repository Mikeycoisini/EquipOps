package com.inventory.export;

import java.nio.file.*;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class InventoryExportFileAccessTest {
    @TempDir Path root;
    @Test void 打开叶文件之前目录已固定并在关闭后释放()throws Exception{
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        String name="9-"+UUID.randomUUID();Path directory=root.resolve(name);Files.createDirectory(directory);Files.write(directory.resolve("inventory.xlsx"),new byte[]{1,2,3,4});Path moved=root.resolve("replacement");
        var access=new InventoryExportFileAccess();
        try(var pin=access.pinDirectory(root,name)){
            // 此刻尚未打开叶文件，正是原来路径校验与文件打开之间的竞争窗口。
            assertThrows(IOException.class,()->Files.move(directory,moved));
            var channel=pin.openFile();assertEquals(4,channel.size());
        }
        Files.move(directory,moved);Files.move(moved,directory);
    }
    @Test void Windows拒绝已有junction目录()throws Exception{
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path target=root.resolve("outside");Files.createDirectory(target);Files.write(target.resolve("inventory.xlsx"),new byte[]{9,9,9,9});
        String name="9-"+UUID.randomUUID();Path link=root.resolve(name);
        var p=new ProcessBuilder("cmd","/c","mklink","/J",link.toString(),target.toString()).redirectErrorStream(true).start();String out=new String(p.getInputStream().readAllBytes());assertEquals(0,p.waitFor(),out);
        try{assertThrows(IOException.class,()->new InventoryExportFileAccess().pinDirectory(root,name));}
        finally{Files.delete(link);}
        assertArrayEquals(new byte[]{9,9,9,9},Files.readAllBytes(target.resolve("inventory.xlsx")));
    }
    @Test void Windows根目录祖先也在文件打开前固定()throws Exception{
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path base=root.resolve("base"),configured=base.resolve("exports");String name="9-"+UUID.randomUUID();
        Path directory=configured.resolve(name);Files.createDirectories(directory);Files.write(directory.resolve("inventory.xlsx"),new byte[]{1,2,3,4});
        try(var pin=new InventoryExportFileAccess().pinDirectory(configured,name)){
            assertThrows(IOException.class,()->Files.move(base,root.resolve("swapped")));
            assertEquals(4,pin.openFile().size());
        }
        Files.move(base,root.resolve("swapped"));
    }
}
