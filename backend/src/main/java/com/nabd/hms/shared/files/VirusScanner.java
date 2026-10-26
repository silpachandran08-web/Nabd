package com.nabd.hms.shared.files;

/** PLT-029: antivirus port. The ClamAV (clamd) adapter runs in the cell and is Needs external action. */
public interface VirusScanner {

    String engine();

    /** true when the content is infected. */
    boolean infected(byte[] data);
}
