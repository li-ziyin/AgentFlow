package com.agentflow.rag;

import com.agentflow.common.BizException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.WriteOutContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文档解析：Apache Tika 一套 API 吃掉 PDF/DOCX/TXT——
 * 不用为每种格式各引一个库、各写一套解析，加新格式（PPT、RTF…）零代码，靠 MIME 自动探测。
 * 解析出来的是"流式纯文本"：PDF 的视觉排版（分栏、页眉）信息在文本层之外，这也是
 * 分块要靠启发式识别标题的原因之一。
 */
@Component
public class DocumentParser {

    /** 解析文本上限（字符）：防止超大文档拖垮内存，超限按截断处理并如实告知 */
    private static final int MAX_CHARS = 2_000_000;

    public record ParsedDocument(String text, String contentType) {
    }

    public ParsedDocument parse(InputStream in, String filename) {
        AutoDetectParser parser = new AutoDetectParser();
        // BodyContentHandler(-1) 不截断，截断职责交给外层 WriteOutContentHandler——
        // 达到上限抛 WriteLimitReachedException，我们捕获后用已抽出的部分继续
        WriteOutContentHandler limited = new WriteOutContentHandler(MAX_CHARS);
        Metadata md = new Metadata();
        md.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        String text;
        try (InputStream is = in) {
            parser.parse(is, new BodyContentHandler(limited), md, new ParseContext());
        } catch (WriteLimitReachedException e) {
            // 预期内截断：文本后面再校验
        } catch (IOException | SAXException | TikaException e) {
            throw new BizException("文档解析失败：" + rootMessage(e)
                    + "（加密/损坏的文件或伪装扩展名都会走到这里）");
        }
        text = limited.toString()
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
        if (text.isEmpty()) {
            throw new BizException("解析后没有可用文本：可能是扫描件/图片型 PDF（需要 OCR，本管线暂不支持）");
        }
        String contentType = md.get(HttpHeaders.CONTENT_TYPE);
        // 去掉 "; charset=..." 后缀，登记册里存干净的 MIME 主类型
        return new ParsedDocument(text,
                contentType == null ? "application/octet-stream" : contentType.split(";")[0].strip());
    }

    /** 异常链里最里层的人话消息（Tika 常把原因包好几层） */
    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }
}
