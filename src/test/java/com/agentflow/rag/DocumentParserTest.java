package com.agentflow.rag;

import com.agentflow.common.BizException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析层测试：PDF 用 PDFBox 现场生成（Tika 的传递依赖，测试类路径自带），
 * TXT/MD 直接字节。钉住三条：PDF 路径能抽文本、MIME 探测正确、坏输入给业务异常。
 */
class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    private byte[] pdfWithLines(String... lines) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 750);
                for (String ln : lines) {
                    cs.showText(ln);
                    cs.newLine();   // 行距用字体大小 × 1.2
                }
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void parsesPdfToText() throws IOException {
        byte[] pdf = pdfWithLines(
                "Chapter 1: Retrieval Pipeline",
                "Structural chunking keeps semantic boundaries intact.");
        DocumentParser.ParsedDocument d = parser.parse(new ByteArrayInputStream(pdf), "test.pdf");
        assertTrue(d.text().contains("Structural chunking keeps semantic"), () -> "实际抽出: " + d.text());
        assertEquals("application/pdf", d.contentType());
    }

    @Test
    void parsesMarkdownAndTxt() {
        DocumentParser.ParsedDocument d = parser.parse(
                new ByteArrayInputStream("# 标题\n\n正文第一段。\n\n正文第二段。".getBytes(StandardCharsets.UTF_8)),
                "doc.md");
        assertTrue(d.text().contains("正文第一段"));
        assertEquals("text/markdown", d.contentType());
    }

    @Test
    void rejectsCorruptedPdfAsBizException() {
        // 有 PDF 魔数但内部损坏：解析必然失败或抽不出文本，都要收敛成业务异常
        byte[] corrupt = "%PDF-1.4\n% corrupted internals, no valid xref, just garbage".getBytes(StandardCharsets.ISO_8859_1);
        assertThrows(BizException.class, () ->
                parser.parse(new ByteArrayInputStream(corrupt), "broken.pdf"));
    }

    @Test
    void plainTextContentWinsOverPdfExtension() {
        // 内容是纯文本、扩展名是 .pdf：Tika 按内容识别为文本——按内容优先，不按名字
        DocumentParser.ParsedDocument d = parser.parse(
                new ByteArrayInputStream("其实只是普通文本".getBytes(StandardCharsets.UTF_8)), "x.pdf");
        assertTrue(d.contentType().startsWith("text/plain"));
        assertTrue(d.text().contains("普通文本"));
    }

    @Test
    void emptyTextIsRejected() throws IOException {
        // 空白 PDF（无文本）：解析成功但没有文本 → 业务异常提示扫描件
        byte[] blank = pdfWithLines();
        assertThrows(BizException.class, () ->
                parser.parse(new ByteArrayInputStream(blank), "blank.pdf"));
    }
}
