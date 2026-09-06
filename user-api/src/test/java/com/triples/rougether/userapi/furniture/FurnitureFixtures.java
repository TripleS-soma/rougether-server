package com.triples.rougether.userapi.furniture;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

public final class FurnitureFixtures {
    private FurnitureFixtures() { }
    public static byte[] png(boolean transparent, boolean clipped) {
        try {
            var image = new BufferedImage(1024, 1024,
                    transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            graphics.setColor(new Color(100, 120, 180));
            graphics.fillRect(clipped ? 0 : 100, 100, 500, 800);
            graphics.dispose();
            var output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
