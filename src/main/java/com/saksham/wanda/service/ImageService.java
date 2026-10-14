package com.saksham.wanda.service;

import com.saksham.wanda.exception.ValidationException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Makes uploaded pictures cheap to send: large photos are scaled down to {@link #MAX_SIDE} pixels
 * and re-encoded as JPEG (or PNG when that is smaller). A 10 MB phone photo becomes a few hundred KB, which uploads, stores and
 * reaches the vision model much faster. Anything unusual (WebP, GIF, unreadable data) is kept as is.
 *
 * @author Saksham Johri
 */
public final class ImageService {

    static final int MAX_SIDE = 1600;
    private static final long MAX_PIXELS = 60_000_000L;
    private static final int KEEP_BELOW_BYTES = 600 * 1024;

    /** A picture ready to send: its MIME type and bytes. */
    public record Prepared(String mime, byte[] bytes) { }

    private ImageService() { }

    public static Prepared prepare(String ext, String mime, byte[] original) {
        if (!ext.equals("png") && !ext.equals("jpg") && !ext.equals("jpeg")) {
            return new Prepared(mime, original);
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return new Prepared(mime, original);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                long width = reader.getWidth(0);
                long height = reader.getHeight(0);
                if (width * height > MAX_PIXELS) {
                    throw new ValidationException("That picture is too large. Use one under 60 megapixels.");
                }
                if (Math.max(width, height) <= MAX_SIDE && original.length <= KEEP_BELOW_BYTES) {
                    return new Prepared(mime, original); // already small
                }
                boolean tooBig = Math.max(width, height) > MAX_SIDE;
                BufferedImage scaled = scale(reader.read(0));
                Prepared best = new Prepared("image/jpeg", toJpeg(scaled));
                if (ext.equals("png")) { // screenshots and diagrams stay crisper as PNG when that is not larger
                    byte[] png = toPng(scaled);
                    if (png.length < best.bytes().length) {
                        best = new Prepared("image/png", png);
                    }
                }
                // Oversized dimensions are always reduced; otherwise keep the original unless it got smaller.
                return tooBig || best.bytes().length < original.length ? best : new Prepared(mime, original);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ValidationException v) {
                throw v;
            }
            return new Prepared(mime, original); // could not decode: send the original untouched
        }
    }

    /** Scales so the longest side is at most MAX_SIDE, flattening transparency onto white. */
    private static BufferedImage scale(BufferedImage src) {
        double ratio = Math.min(1.0, (double) MAX_SIDE / Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * ratio));
        int h = Math.max(1, (int) Math.round(src.getHeight() * ratio));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static byte[] toJpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
                writer.setOutput(out);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
