package com.saksham.wanda.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageServiceTest {

    private static byte[] png(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                img.setRGB(x, y, ((x / 40 + y / 40) % 2 == 0) ? 0xC8102E : 0x111111);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void shrinksHugePicturesToTheLimit() throws IOException {
        byte[] original = png(2400, 1800);
        ImageService.Prepared p = ImageService.prepare("png", "image/png", original);
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(p.bytes()));
        assertEquals(ImageService.MAX_SIDE, Math.max(result.getWidth(), result.getHeight()));
        assertEquals(1200, Math.min(result.getWidth(), result.getHeight()));
    }

    @Test
    void leavesSmallPicturesUntouched() throws IOException {
        byte[] original = png(64, 64);
        ImageService.Prepared p = ImageService.prepare("png", "image/png", original);
        assertEquals("image/png", p.mime());
        assertArrayEquals(original, p.bytes());
    }

    @Test
    void keepsUnreadableAndUnsupportedFormatsAsTheyAre() {
        byte[] junk = {1, 2, 3, 4, 5};
        assertArrayEquals(junk, ImageService.prepare("png", "image/png", junk).bytes());
        assertArrayEquals(junk, ImageService.prepare("webp", "image/webp", junk).bytes());
        assertTrue(ImageService.prepare("gif", "image/gif", junk).mime().contains("gif"));
    }
}
