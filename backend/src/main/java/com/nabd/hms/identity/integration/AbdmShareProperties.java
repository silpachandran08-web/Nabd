package com.nabd.hms.identity.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** IDC-018 app.abdm.share.*: where the token answer to a Scan & Share goes, below app.abdm.base-url. Blank = not configured: the answer is not sent (Needs external action, DEP-05). */
@ConfigurationProperties(prefix = "app.abdm.share")
public record AbdmShareProperties(String onSharePath) {

    public AbdmShareProperties {
        onSharePath = onSharePath == null ? "" : onSharePath;
    }
}
