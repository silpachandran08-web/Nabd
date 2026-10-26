package com.nabd.hms.shared.files;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** PLT-029: magic-byte type detection (allow-list PDF, JPEG, PNG) and JPEG metadata stripping. HEIC and DICOM are not accepted yet. */
final class FileSniffer {
    private FileSniffer() {
    }

    /** The detected content type, or null when the bytes are not on the allow-list (executables included). */
    static String detect(byte[] d) {
        if (d.length >= 5 && new String(d, 0, 5, StandardCharsets.ISO_8859_1).equals("%PDF-")) {
            return "application/pdf";
        }
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G' && d[4] == 0x0D && d[5] == 0x0A
                && d[6] == 0x1A && d[7] == 0x0A) {
            return "image/png";
        }
        return null;
    }

    /** Drops every APP1..APP15 segment (EXIF with GPS, XMP, ICC) and comments; keeps JFIF (APP0) and the image data. */
    static byte[] stripJpegMetadata(byte[] d) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
        out.write(0xFF);
        out.write(0xD8);
        int i = 2;
        while (i + 3 < d.length) {
            if ((d[i] & 0xFF) != 0xFF) {
                break;
            }
            int marker = d[i + 1] & 0xFF;
            if (marker == 0xDA) { // start of scan: the rest is image data
                break;
            }
            int len = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
            if (len < 2 || i + 2 + len > d.length) {
                break;
            }
            boolean metadata = (marker >= 0xE1 && marker <= 0xEF) || marker == 0xFE;
            if (!metadata) {
                out.write(d, i, 2 + len);
            }
            i += 2 + len;
        }
        out.write(d, i, d.length - i);
        return out.toByteArray();
    }
}
