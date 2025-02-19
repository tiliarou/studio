/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1.utils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import com.jhlabs.image.QuantizeFilter;

public class ImageConversion {

    private static final String BITMAP_FORMAT = "BMP";
    private static final String PNG_FORMAT = "PNG";
    private static final String BITMAP_RLE4_COMPRESSION = "BI_RLE4";

    // Load deferred color space profiles to avoid ConcurrentModificationException
    // due to JDK
    // Use in public static main void or prior to application initialization
    // https://github.com/haraldk/TwelveMonkeys/issues/402
    // https://bugs.openjdk.java.net/browse/JDK-6986863
    // https://stackoverflow.com/questions/26297491/imageio-thread-safety
    static {
        ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
        ICC_Profile.getInstance(ColorSpace.CS_PYCC).getData();
        ICC_Profile.getInstance(ColorSpace.CS_GRAY).getData();
        ICC_Profile.getInstance(ColorSpace.CS_CIEXYZ).getData();
        ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB).getData();
    }

    private ImageConversion() {
        throw new IllegalArgumentException("Utility class");
    }

    public static byte[] anyToBitmap(byte[] data) throws IOException {
        return convertImage(data, BITMAP_FORMAT);
    }

    public static byte[] bitmapToPng(byte[] bmpData) throws IOException {
        return convertImage(bmpData, PNG_FORMAT);
    }

    public static byte[] convertImage(byte[] data, String format) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            BufferedImage img = ImageIO.read(bais);
            // Redraw image to remove potential alpha channel
            BufferedImage redrawn = redrawImage(img);
            ImageIO.write(redrawn, format, baos);
            if (baos.size() == 0) {
                throw new IOException("Failed to convert image");
            }
            return baos.toByteArray();
        }
    }

    private static BufferedImage redrawImage(BufferedImage img) {
        BufferedImage redrawn = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = redrawn.createGraphics();
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, redrawn.getWidth(), redrawn.getHeight());
        g2d.drawImage(img, 0, 0, null);
        g2d.dispose();
        return redrawn;
    }

    public static byte[] anyToRLECompressedBitmap(byte[] data) throws IOException {
        // image byte buffer
        ByteBuffer bb;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            // read image
            BufferedImage img = ImageIO.read(bais);
            // Redraw image to remove potential alpha channel, and to used indexed colors
            BufferedImage redrawn = redrawIndexedImage(img);

            // write BMP
            ImageWriter writer = ImageIO.getImageWritersByFormatName(BITMAP_FORMAT).next();
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            writeParam.setCompressionType(BITMAP_RLE4_COMPRESSION);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(redrawn, null, null), writeParam);
            // to bytes
            if (baos.size() == 0) {
                throw new IOException("Failed to convert image");
            }
            bb = ByteBuffer.wrap(baos.toByteArray());
        }
        // BMPImageWriter outputs wrong padding on absolute-mode chunks that needs to be
        // fixed
        return fixRLE4Padding(bb);
    }

    private static byte[] fixRLE4Padding(ByteBuffer bb) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            // Copy header
            for (int i = 0; i < 0x76; i++) {
                baos.write(bb.get());
            }
            while (bb.hasRemaining()) {
                // Copy 2-bytes chunk
                byte b1 = bb.get();
                byte b2 = bb.get();
                baos.write(b1);
                baos.write(b2);
                // Handle absolute mode
                if (b1 == 0x00 && (b2 & 0xff) > 0x02) {
                    int length = b2 & 0xff;
                    byte lengthInBytes = (byte) Math.ceil(length / 2.0);
                    // Copy pixels
                    for (int i = 0; i < lengthInBytes; i++) {
                        baos.write(bb.get());
                    }
                    // Fix wrong alignment
                    int wrongByteLength = length / 2;
                    if (wrongByteLength % 2 == 0 && lengthInBytes % 2 == 1) {
                        // Fix: Add missing padding byte
                        baos.write(0x00);
                    } else if (wrongByteLength % 2 == 1 && lengthInBytes % 2 == 0) {
                        // Fix: Remove unneeded padding byte
                        bb.get();
                    } else if (lengthInBytes % 2 == 1) {
                        // Copy legit padding byte
                        baos.write(bb.get());
                    }
                }
            }
            return baos.toByteArray();
        }
    }

    private static BufferedImage redrawIndexedImage(BufferedImage img) {
        // Quantize image to 16 colors max
        QuantizeFilter quantizeFilter = new QuantizeFilter();
        quantizeFilter.setNumColors(16);
        quantizeFilter.setDither(true);
        quantizeFilter.setSerpentine(true);
        BufferedImage outputImage = quantizeFilter.filter(img, null);

        // Extract palette from quantized image
        int[] rgb = outputImage.getRGB(0, 0, outputImage.getWidth(), outputImage.getHeight(), null, 0,
                outputImage.getWidth());
        int[] cmap = Arrays.stream(rgb).distinct().toArray();

        // Force 16-colors palette
        int[] cmap16 = Arrays.copyOf(cmap, 16);

        // Create indexed image with palette
        BufferedImage redrawn = new BufferedImage(outputImage.getWidth(), outputImage.getHeight(),
                BufferedImage.TYPE_BYTE_INDEXED, new IndexColorModel(4, cmap16.length, cmap16, 0, false, -1, 0));
        Graphics2D g2d = redrawn.createGraphics();
        g2d.fillRect(0, 0, redrawn.getWidth(), redrawn.getHeight());
        g2d.drawImage(outputImage, 0, 0, null);
        g2d.dispose();
        return redrawn;
    }
}
