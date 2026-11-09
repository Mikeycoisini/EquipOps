package com.inventory.export;

import com.equipops.domain.vo.InventoryTransactionVO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.format.DateTimeFormatter;
import java.util.zip.*;

/** 单表流式OOXML：只持有当前一行，不缓存整个结果，不使用公式或共享字符串表。 */
public final class InventoryXlsxWriter implements AutoCloseable {
    private final ZipOutputStream zip;
    private final Writer xml;
    private int rowNumber = 1;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] HEADERS = {"流水编号","流水类型","仓库编号","仓库名称","备件编号","备件名称",
            "变动数量","来源类型","来源说明","工单编号","原领料流水ID","操作人ID","备注","创建时间"};

    public InventoryXlsxWriter(Path file) throws IOException {
        // 1. 独占创建受控临时文件；依次写入OOXML关系、工作簿、格式和工作表表头。
        zip = new ZipOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE_NEW));
        xml = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
        try {
            entry("[Content_Types].xml", """
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>
                """);
            entry("_rels/.rels", """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>
                """);
            entry("xl/workbook.xml", """
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets><sheet name="库存流水" sheetId="1" r:id="rId1"/></sheets></workbook>
                """);
            entry("xl/_rels/workbook.xml.rels", """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>
                """);
            entry("xl/styles.xml", """
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <numFmts count="1"><numFmt numFmtId="164" formatCode="0.000"/></numFmts>
                <fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
                <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
                <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
                <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                <cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
                <xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs>
                <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>
                """);
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            xml.write("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><cols><col min=\"1\" max=\"14\" width=\"22\" customWidth=\"1\"/></cols><sheetData><row r=\"1\">");
            for (int i=0;i<HEADERS.length;i++) text(i,HEADERS[i]);
            xml.write("</row>");
        } catch (IOException | RuntimeException failure) {
            try { zip.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    public void append(InventoryTransactionVO row) throws IOException {
        // 1. 按固定14列写一行；ID写字符串防止精度丢失，数量写数值并显示三位小数。
        rowNumber++;
        xml.write("<row r=\""+rowNumber+"\">");
        text(0,row.getTransactionNo());
        text(1,row.getIssueTransactionId()!=null ? "退料" : row.getTransactionType());
        text(2,row.getWarehouseCode()); text(3,row.getWarehouseName());
        text(4,row.getPartCode()); text(5,row.getPartName());
        if(row.getQuantity()!=null) xml.write("<c r=\"G"+rowNumber+"\" s=\"1\" t=\"n\"><v>"+row.getQuantity().toPlainString()+"</v></c>");
        text(7,row.getReferenceType()); text(8,row.getReferenceText()); text(9,row.getWorkOrderNo());
        text(10,row.getIssueTransactionId()); text(11,row.getCreatedBy()); text(12,row.getNote());
        text(13,row.getCreatedAt()==null?null:TIME.format(row.getCreatedAt()));
        xml.write("</row>");
    }
    private void text(int column, Object value) throws IOException {
        // 1. 全部用户文本写inlineStr，绝不解释为公式；剔除XML不允许的控制字符。
        if(value==null) return;
        String text=value.toString();
        if(text.length()>32767) throw new IOException("Excel单元格文本超过上限");
        xml.write("<c r=\""+(char)('A'+column)+rowNumber+"\" t=\"inlineStr\"><is><t xml:space=\"preserve\">");
        for(int i=0;i<text.length();) {
            int c=text.codePointAt(i); i+=Character.charCount(c);
            if(c=='&') xml.write("&amp;"); else if(c=='<') xml.write("&lt;"); else if(c=='>') xml.write("&gt;");
            else if(c==9 || c==10 || c==13 || c>=32 && c<=0xD7FF || c>=0xE000 && c<=0xFFFD || c>=0x10000 && c<=0x10FFFF) xml.write(Character.toChars(c));
        }
        xml.write("</t></is></c>");
    }
    private void entry(String name,String contents) throws IOException {
        // 1. 写完并刷出当前XML后关闭ZIP条目，避免缓冲跨条目。
        zip.putNextEntry(new ZipEntry(name)); xml.write(contents); xml.flush(); zip.closeEntry();
    }
    public void flush() throws IOException { xml.flush(); }
    @Override public void close() throws IOException {
        // 1. 完成表尾后始终关闭底层流；异常由调用方删除整个临时文件。
        try { xml.write("</sheetData></worksheet>"); xml.flush(); zip.closeEntry(); }
        finally { zip.close(); }
    }
}
