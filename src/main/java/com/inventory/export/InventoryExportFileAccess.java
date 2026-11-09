package com.inventory.export;

import com.sun.jna.*;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 在打开叶文件之前固定目录；不把“检查过路径”当作“打开时路径仍然相同”。 */
@Component
public final class InventoryExportFileAccess {
    public PinnedDirectory pinDirectory(Path configured,String name)throws IOException {
        // 1. 根目录为服务器配置，任务目录只能是一层ID-UUID；从文件系统根开始固定每层目录。
        if(!configured.isAbsolute() || !name.matches("[1-9][0-9]*-[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))throw new IOException("Invalid controlled directory");
        Path root=configured.toAbsolutePath().normalize();
        var pins=new ArrayList<AutoCloseable>();
        try {
            // 2. Windows使用不共享DELETE的目录句柄；固定根目录后再固定子目录，检查实际句柄不是重解析点。
            if(System.getProperty("os.name").startsWith("Windows")) {
                Path cursor=root.getRoot();
                pins.add(WindowsPin.open(cursor));
                // 固定所有祖先，不能只固定最后两层后又通过可被改名的祖先绝对路径打开文件。
                for(Path component:root){cursor=cursor.resolve(component);pins.add(WindowsPin.open(cursor));}
                Path directory=root.resolve(name);
                pins.add(WindowsPin.open(directory));
                return new PinnedDirectory(directory.resolve("inventory.xlsx"),null,pins);
            }
            // 3. 其他平台只接受支持安全相对打开的提供器，目录即使改名也不会改变已打开目录的目标。
            DirectoryStream<Path> opened=Files.newDirectoryStream(root.getRoot());pins.add(opened);
            if(!(opened instanceof SecureDirectoryStream<Path> secure))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"当前文件系统不支持安全下载");
            SecureDirectoryStream<Path> current=secure;
            for(Path component:root){current=current.newDirectoryStream(component,LinkOption.NOFOLLOW_LINKS);pins.add(current);}
            SecureDirectoryStream<Path> child=current.newDirectoryStream(root.getFileSystem().getPath(name),LinkOption.NOFOLLOW_LINKS);pins.add(child);
            return new PinnedDirectory(root.resolve(name).resolve("inventory.xlsx"),child,pins);
        } catch(IOException | RuntimeException | Error failure) {
            // 4. 部分目录打开后失败也关闭已取得的句柄，不降级回普通绝对路径打开。
            closeAll(pins,failure);throw failure;
        }
    }

    public static final class PinnedDirectory implements AutoCloseable {
        private final Path file;
        private final SecureDirectoryStream<Path> relative;
        private final List<AutoCloseable> pins;
        private SeekableByteChannel channel;
        private boolean closed;
        private PinnedDirectory(Path file,SecureDirectoryStream<Path> relative,List<AutoCloseable> pins){this.file=file;this.relative=relative;this.pins=pins;}

        public synchronized SeekableByteChannel openFile()throws IOException {
            // 1. 只能在目录句柄有效时打开固定叶文件；不允许重复打开或复用已关闭目录。
            if(closed || channel!=null)throw new IOException("Directory handle unavailable");
            Path leaf=file.getFileSystem().getPath("inventory.xlsx");
            BasicFileAttributes attributes=relative==null
                    ?Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS)
                    :relative.getFileAttributeView(leaf,BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();
            if(!attributes.isRegularFile() || attributes.isSymbolicLink())throw new IOException("Not a regular export file");
            // 2. Windows父目录仍被禁止改名/替换；安全目录平台则相对于目录句柄打开，不跟随叶链接。
            channel=relative==null
                    ?Files.newByteChannel(file,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))
                    :relative.newByteChannel(leaf,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));
            return channel;
        }

        @Override public synchronized void close()throws IOException {
            // 1. 文件通道先关闭，随后逆序关闭子目录与根目录句柄；各项失败仍继续释放其余资源。
            if(closed)return;closed=true;IOException failure=null;
            if(channel!=null)try{channel.close();}catch(IOException e){failure=e;}
            for(int i=pins.size()-1;i>=0;i--)try{pins.get(i).close();}catch(Exception e){if(failure==null)failure=new IOException("Directory handle close failed",e);else failure.addSuppressed(e);}
            if(failure!=null)throw failure;
        }
    }

    private static void closeAll(List<AutoCloseable> pins,Throwable failure) {
        // 1. 构造失败按相反顺序关闭已打开资源，保留原失败原因。
        for(int i=pins.size()-1;i>=0;i--)try{pins.get(i).close();}catch(Exception close){failure.addSuppressed(close);}
    }

    public interface WindowsApi extends StdCallLibrary {
        Pointer CreateFileW(WString name,int access,int sharing,Pointer security,int creation,int flags,Pointer template);
        int GetFileInformationByHandle(Pointer handle,Pointer information);
        int CloseHandle(Pointer handle);
    }
    private static final class WindowsNative {
        static final WindowsApi API=Native.load("kernel32",WindowsApi.class);
    }
    private static final class WindowsPin implements AutoCloseable {
        private final Pointer handle;
        private WindowsPin(Pointer handle){this.handle=handle;}
        static WindowsPin open(Path directory)throws IOException {
            // 1. 以LIST_DIRECTORY和READ_ATTRIBUTES打开目录本身，BACKUP_SEMANTICS允许目录句柄，OPEN_REPARSE_POINT不跟随链接。
            String path=directory.toString();
            if(!path.startsWith("\\\\?\\"))path=path.startsWith("\\\\")?"\\\\?\\UNC\\"+path.substring(2):"\\\\?\\"+path;
            Pointer h=WindowsNative.API.CreateFileW(new WString(path),0x1|0x80,0x1|0x2,Pointer.NULL,3,0x02000000|0x00200000,Pointer.NULL);
            if(h==null || Pointer.nativeValue(h)==-1L)throw new IOException("Directory pin failed, code="+Native.getLastError());
            WindowsPin pin=new WindowsPin(h);
            try(var info=new Memory(52)) {
                // 2. 检查实际句柄属性；拒绝非目录以及junction、symlink等重解析点。
                if(WindowsNative.API.GetFileInformationByHandle(h,info)==0)throw new IOException("Directory inspection failed, code="+Native.getLastError());
                int attributes=info.getInt(0);
                if((attributes&0x10)==0 || (attributes&0x400)!=0)throw new IOException("Directory reparse point rejected");
                return pin;
            } catch(IOException | RuntimeException | Error failure) {
                pin.close();throw failure;
            }
        }
        @Override public void close()throws IOException {
            // 1. 释放禁止DELETE共享的句柄后，目录恢复正常改名和清理能力。
            if(WindowsNative.API.CloseHandle(handle)==0)throw new IOException("Directory close failed, code="+Native.getLastError());
        }
    }
}
