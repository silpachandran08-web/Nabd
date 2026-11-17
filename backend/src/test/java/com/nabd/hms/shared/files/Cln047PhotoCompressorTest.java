package com.nabd.hms.shared.files;

import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;

/** CLN-047 AC5 (no Spring): a 12 MP phone photo is stored at most 1.5 MB with the EXIF location gone; bombs and junk are refused. */
class Cln047PhotoCompressorTest {

    /** A synthetic 12 MP (4000 x 3000) camera-like image: gradients plus sensor noise, so it compresses like a real photo. */
    static BufferedImage twelveMegapixels() {
        BufferedImage img = new BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(47);
        for (int y = 0; y < 3000; y++) {
            for (int x = 0; x < 4000; x++) {
                int n = r.nextInt(48);
                int red = Math.min(255, x * 200 / 4000 + n), green = Math.min(255, y * 180 / 3000 + n), blue = Math.min(255, 90 + n);
                img.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        return img;
    }

    static byte[] jpeg(BufferedImage img, float q) throws IOException {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ImageOutputStream o = ImageIO.createImageOutputStream(b)) {
            w.setOutput(o);
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(q);
            w.write(null, new IIOImage(img, null, null), p);
        }
        w.dispose();
        return b.toByteArray();
    }

    /** The JPEG with an APP1 Exif segment (GPS position) right after SOI, as a phone writes it. */
    static byte[] withGps(byte[] jpeg) {
        byte[] exif = "Exif\0\0GPSLatitude=25.2854;GPSLongitude=51.5310".getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xFF); o.write(0xD8);
        o.write(0xFF); o.write(0xE1); o.write((exif.length + 2) >> 8); o.write((exif.length + 2) & 0xFF); o.writeBytes(exif);
        o.write(jpeg, 2, jpeg.length - 2);
        return o.toByteArray();
    }

    @Test
    void ac5_aTwelveMegapixelPhoneJpegIsStoredUnder1_5MbWithoutExifLocation() throws IOException {
        byte[] phone = withGps(jpeg(twelveMegapixels(), 0.95f));
        assertThat(phone.length).as("precondition: bigger than the limit").isGreaterThan(3_000_000);
        assertThat(new String(phone, StandardCharsets.ISO_8859_1)).contains("GPSLatitude");

        byte[] stored = PhotoCompressor.fit(phone, "image/jpeg");

        assertThat(stored.length).isLessThanOrEqualTo(1_500_000);
        String s = new String(stored, StandardCharsets.ISO_8859_1);
        assertThat(s).doesNotContain("GPS").doesNotContain("Exif");
        assertThat(FileSniffer.detect(stored)).isEqualTo("image/jpeg");
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(stored));
        assertThat(back).isNotNull();
        assertThat(back.getWidth() * 3).isEqualTo(back.getHeight() * 4); // aspect kept
    }

    @Test
    void aSmallJpegKeepsItsPixelsAndOnlyLosesMetadata() throws IOException {
        BufferedImage img = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        byte[] plain = jpeg(img, 0.9f);
        byte[] stored = PhotoCompressor.fit(withGps(plain), "image/jpeg");
        assertThat(stored).isEqualTo(FileSniffer.stripJpegMetadata(plain));
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("GPS");
    }

    @Test
    void aPngIsReencodedAsJpeg_soItsTextChunksCannotCarryLocation() throws IOException {
        BufferedImage img = new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(img, "png", b);
        byte[] stored = PhotoCompressor.fit(b.toByteArray(), "image/png");
        assertThat(FileSniffer.detect(stored)).isEqualTo("image/jpeg");
        assertThat(ImageIO.read(new ByteArrayInputStream(stored)).getWidth()).isEqualTo(200);
    }

    @Test
    void aDecompressionBombOrJunkIsRefused() {
        assertThat(PhotoCompressor.fit(pngHeader(100_000, 100_000), "image/png")).isNull();
        byte[] junk = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
        assertThat(PhotoCompressor.fit(junk, "image/png")).isNull();
    }

    /** A PNG signature + IHDR that claims width x height, and nothing else. */
    private static byte[] pngHeader(int w, int h) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        byte[] ihdr = {'I', 'H', 'D', 'R', (byte) (w >>> 24), (byte) (w >>> 16), (byte) (w >>> 8), (byte) w,
                (byte) (h >>> 24), (byte) (h >>> 16), (byte) (h >>> 8), (byte) h, 8, 2, 0, 0, 0};
        o.writeBytes(new byte[]{0, 0, 0, 13});
        o.writeBytes(ihdr);
        CRC32 crc = new CRC32();
        crc.update(ihdr);
        long c = crc.getValue();
        o.writeBytes(new byte[]{(byte) (c >>> 24), (byte) (c >>> 16), (byte) (c >>> 8), (byte) c});
        return o.toByteArray();
    }
}
