package com.saksham.wanda.service;

import com.saksham.wanda.exception.ValidationException;
import com.saksham.wanda.model.Attachment;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns an uploaded file into something the model can read:
 * text and code files, PDF, Word, PowerPoint and Excel files become text;
 * pictures become data URLs for the vision model.
 *
 * @author Saksham Johri
 */
public class FileService {

    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_CHARS = 60_000;

    private static final Set<String> TEXT_TYPES = Set.of(
            "txt", "md", "csv", "tsv", "json", "xml", "html", "css", "js", "ts", "jsx", "tsx", "java",
            "py", "c", "cpp", "h", "cs", "go", "rs", "rb", "php", "kt", "swift", "sql", "sh", "yml",
            "yaml", "properties", "ini", "log");

    private static final Map<String, String> IMAGE_TYPES = Map.of(
            "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
            "webp", "image/webp", "gif", "image/gif");

    public Attachment process(String fileName, byte[] bytes) {
        if (bytes.length == 0) {
            throw new ValidationException("The file is empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new ValidationException(413, "Files must be 10 MB or smaller");
        }
        String name = fileName == null || fileName.isBlank() ? "file" : fileName;
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        String id = UUID.randomUUID().toString();

        if (IMAGE_TYPES.containsKey(ext)) {
            ImageService.Prepared picture = ImageService.prepare(ext, IMAGE_TYPES.get(ext), bytes);
            String dataUrl = "data:" + picture.mime() + ";base64," + Base64.getEncoder().encodeToString(picture.bytes());
            return new Attachment(id, name, Attachment.Kind.IMAGE, null, dataUrl, bytes.length);
        }

        String text = switch (ext) {
            case "pdf" -> pdfText(bytes);
            case "docx" -> docxText(officeParts(bytes, "docx"));
            case "pptx" -> pptxText(officeParts(bytes, "pptx"));
            case "xlsx" -> xlsxText(officeParts(bytes, "xlsx"));
            case "doc", "ppt", "xls" -> throw new ValidationException(415,
                    "Old Office formats aren't supported. Save the file as ." + ext + "x and upload it again.");
            default -> {
                if (!TEXT_TYPES.contains(ext) && containsBinary(bytes)) {
                    throw new ValidationException(415,
                            "Unsupported file type. Use text, code, PDF, Word, PowerPoint, Excel or image files.");
                }
                yield new String(bytes, StandardCharsets.UTF_8);
            }
        };
        if (text.isBlank()) {
            throw new ValidationException("No readable text was found in " + name);
        }
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS) + "\n[... file truncated ...]";
        }
        return new Attachment(id, name, Attachment.Kind.TEXT, text, null, bytes.length);
    }

    private static String pdfText(byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        } catch (IOException e) {
            throw new ValidationException("Could not read this PDF (it may be encrypted or scanned)");
        }
    }

    /**
     * Word, PowerPoint and Excel files are zip archives of XML parts.
     * Returns the XML parts by name, sorted so slides and sheets keep their order.
     */
    private static Map<String, String> officeParts(byte[] bytes, String ext) {
        Map<String, String> parts = new TreeMap<>(Comparator.comparingInt(FileService::partNumber)
                .thenComparing(Comparator.naturalOrder()));
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String name = e.getName();
                if (name.endsWith(".xml") && (name.startsWith("word/") || name.startsWith("ppt/slides/")
                        || name.startsWith("xl/worksheets/") || name.equals("xl/sharedStrings.xml"))) {
                    parts.put(name, new String(zip.readNBytes(MAX_BYTES), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new ValidationException("Could not read this ." + ext + " file");
        }
        if (parts.isEmpty()) {
            throw new ValidationException("This is not a valid ." + ext + " file");
        }
        return parts;
    }

    private static String docxText(Map<String, String> parts) {
        String xml = parts.get("word/document.xml");
        if (xml == null) {
            throw new ValidationException("This is not a valid .docx file");
        }
        return plain(xml.replace("</w:p>", "\n"));
    }

    private static String pptxText(Map<String, String> parts) {
        StringBuilder sb = new StringBuilder();
        parts.forEach((name, xml) -> {
            if (name.matches("ppt/slides/slide\\d+\\.xml")) {
                sb.append("Slide ").append(partNumber(name)).append(":\n")
                  .append(plain(xml.replace("</a:p>", "\n"))).append("\n");
            }
        });
        return sb.toString();
    }

    /** Rebuilds each sheet as tab-separated rows; text cells point into the shared string table. */
    private static String xlsxText(Map<String, String> parts) {
        List<String> shared = new ArrayList<>();
        Matcher si = Pattern.compile("<si>(.*?)</si>", Pattern.DOTALL)
                .matcher(parts.getOrDefault("xl/sharedStrings.xml", ""));
        while (si.find()) {
            shared.add(plain(si.group(1)));
        }
        Pattern row = Pattern.compile("<row[^>]*>(.*?)</row>", Pattern.DOTALL);
        Pattern cell = Pattern.compile("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", Pattern.DOTALL);
        Pattern value = Pattern.compile("<v>(.*?)</v>|<t[^>]*>(.*?)</t>", Pattern.DOTALL);
        StringBuilder sb = new StringBuilder();
        parts.forEach((name, xml) -> {
            if (!name.startsWith("xl/worksheets/sheet")) {
                return;
            }
            sb.append("Sheet ").append(partNumber(name)).append(":\n");
            Matcher r = row.matcher(xml);
            while (r.find()) {
                List<String> cells = new ArrayList<>();
                Matcher c = cell.matcher(r.group(1));
                while (c.find()) {
                    Matcher v = value.matcher(c.group(2) == null ? "" : c.group(2));
                    String text = !v.find() ? "" : v.group(1) != null ? v.group(1) : plain(v.group(2));
                    if (c.group(1).contains("t=\"s\"") && text.matches("\\d+") && Integer.parseInt(text) < shared.size()) {
                        text = shared.get(Integer.parseInt(text));
                    }
                    cells.add(text);
                }
                sb.append(String.join("\t", cells)).append("\n");
            }
        });
        return sb.toString();
    }

    /** Strips XML tags and decodes the basic entities. */
    private static String plain(String xml) {
        return xml.replaceAll("<[^>]+>", "").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    /** Number inside a part name such as slide12.xml (0 when there is none). */
    private static int partNumber(String name) {
        Matcher m = Pattern.compile("(\\d+)\\.xml$").matcher(name);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static boolean containsBinary(byte[] bytes) {
        int n = Math.min(bytes.length, 4096);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) return true;
        }
        return false;
    }
}
