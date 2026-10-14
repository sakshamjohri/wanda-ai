package com.saksham.wanda.service;

import com.saksham.wanda.exception.ValidationException;
import com.saksham.wanda.model.Attachment;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileServiceTest {

    private final FileService files = new FileService();

    private static byte[] zip(Map<String, String> parts) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> e : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void readsPlainTextAndCode() {
        Attachment a = files.process("notes.txt", "Hello PINEAPPLE".getBytes(StandardCharsets.UTF_8));
        assertEquals(Attachment.Kind.TEXT, a.kind());
        assertEquals("Hello PINEAPPLE", a.text());
    }

    @Test
    void readsWordDocuments() throws IOException {
        byte[] docx = zip(Map.of("word/document.xml",
                "<w:document><w:body><w:p><w:r><w:t>Hello docx</w:t></w:r></w:p></w:body></w:document>"));
        assertTrue(files.process("a.docx", docx).text().contains("Hello docx"));
    }

    @Test
    void readsPowerPointSlides() throws IOException {
        byte[] pptx = zip(Map.of("ppt/slides/slide1.xml", "<p:sld><a:p><a:r><a:t>Slide one</a:t></a:r></a:p></p:sld>"));
        String text = files.process("a.pptx", pptx).text();
        assertTrue(text.contains("Slide 1") && text.contains("Slide one"));
    }

    @Test
    void readsExcelSheetsWithSharedStrings() throws IOException {
        byte[] xlsx = zip(Map.of(
                "xl/sharedStrings.xml", "<sst><si><t>Name</t></si><si><t>Alice</t></si></sst>",
                "xl/worksheets/sheet1.xml", "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c>"
                        + "<c r=\"B1\"><v>42</v></c></row><row r=\"2\"><c r=\"A2\" t=\"s\"><v>1</v></c></row></sheetData></worksheet>"));
        String text = files.process("a.xlsx", xlsx).text();
        assertTrue(text.contains("Name\t42") && text.contains("Alice"));
    }

    @Test
    void rejectsEmptyOversizedLegacyAndBinaryFiles() {
        assertEquals(400, assertThrows(ValidationException.class, () -> files.process("a.txt", new byte[0])).getStatus());
        assertEquals(413, assertThrows(ValidationException.class,
                () -> files.process("a.txt", new byte[FileService.MAX_BYTES + 1])).getStatus());
        assertEquals(415, assertThrows(ValidationException.class, () -> files.process("a.doc", new byte[]{1, 2, 3})).getStatus());
        assertEquals(415, assertThrows(ValidationException.class,
                () -> files.process("a.bin", new byte[]{0, 1, 2, 3})).getStatus());
    }

    @Test
    void acceptsPicturesAsDataUrls() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
        Attachment a = files.process("pic.png", png);
        assertEquals(Attachment.Kind.IMAGE, a.kind());
        assertTrue(a.dataUrl().startsWith("data:image/png;base64,"));
    }
}
