package com.nabd.hms.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** OPS-068: archive files under app.audit.archive.dir. Not a production store: no object lock, no region guarantee. */
@Component
class LocalAuditArchiveStore implements AuditArchiveStore {

    private final String dir;

    LocalAuditArchiveStore(@Value("${app.audit.archive.dir:}") String dir) {
        this.dir = dir;
    }

    @Override
    public void put(String key, Path file) throws IOException {
        Path to = path(key);
        Files.createDirectories(to.getParent());
        Files.copy(file, to, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public InputStream get(String key) throws IOException {
        return Files.newInputStream(path(key));
    }

    private Path path(String key) {
        if (dir.isBlank()) {
            throw new IllegalStateException("app.audit.archive.dir is not configured: there is no archive store");
        }
        Path root = Path.of(dir).toAbsolutePath().normalize();
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("archive key leaves the archive directory");
        }
        return p;
    }
}
