package com.nabd.hms.shared.files;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * CLN-047: a clinical photo is stored at most {@link #MAX_BYTES} with no metadata. A JPEG already under the limit keeps its
 * pixels and loses every APPn segment (EXIF with GPS, XMP); anything else (bigger, or PNG, whose eXIf/tEXt chunks may carry
 * location) is re-encoded with the JDK's ImageIO, which writes JFIF only, lowering quality and then size until it fits.
 * shortcut: the EXIF orientation is dropped, not applied; staff-web bakes it into the pixels before upload (canvas).
 */
final class PhotoCompressor {

    static final long MAX_BYTES = 1_500_000;
    /** Decompression-bomb guard: a header may claim any size; 50 MP is well above a phone camera. */
    static final long MAX_PIXELS = 50_000_000L;
    private static final float[] QUALITIES = {0.85f, 0.75f, 0.65f};
    private static final int MIN_EDGE = 320;

    private PhotoCompressor() {
    }

    /** The bytes to store, or null when the data is not a decodable JPEG/PNG within the pixel limit. */
    static byte[] fit(byte[] data, String detectedType) {
        if ("image/jpeg".equals(detectedType) && data.length <= MAX_BYTES) {
            return FileSniffer.stripJpegMetadata(data);
        }
        BufferedImage img = decode(data);
        if (img == null) {
            return null;
        }
        BufferedImage rgb = flatten(img, img.getWidth(), img.getHeight());
        while (true) {
            byte[] out = null;
            for (float q : QUALITIES) {
                out = jpeg(rgb, q);
                if (out.length <= MAX_BYTES) {
                    return out;
                }
            }
            int w = rgb.getWidth() * 3 / 4, h = rgb.getHeight() * 3 / 4;
            if (Math.max(w, h) < MIN_EDGE) {
                return out; // unreachable for real photos: a 320 px JPEG is a few tens of KB
            }
            rgb = flatten(rgb, w, h);
        }
    }

    private static BufferedImage decode(byte[] data) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                return null;
            }
            ImageReader r = readers.next();
            try {
                r.setInput(in, true, true); // ignore metadata: it is never read, so it can never be logged or kept
                if ((long) r.getWidth(0) * r.getHeight(0) > MAX_PIXELS) {
                    return null;
                }
                return r.read(0);
            } finally {
                r.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null; // corrupt or unsupported (CMYK JPEG): the scan marks it rejected_type
        }
    }

    /** RGB copy at the given size (JPEG has no alpha: transparent pixels become white). */
    private static BufferedImage flatten(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] jpeg(BufferedImage img, float quality) {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            w.setOutput(out);
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            w.write(null, new IIOImage(img, null, null), p);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            w.dispose();
        }
        return bytes.toByteArray();
    }
}
