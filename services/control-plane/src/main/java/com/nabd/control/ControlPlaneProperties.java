package com.nabd.control;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@ConfigurationProperties("control-plane")
public record ControlPlaneProperties(
        /** Cell codes (in, ae, sa, qa) whose organization stream this instance reads. */
        @DefaultValue("in") List<String> regions,
        @DefaultValue("control-plane-1") String consumerName,
        @DefaultValue Copy copy) {

    /** The one-off cut-over job (MasterCopyJob). Credentials come from the environment, never from a file. */
    public record Copy(@DefaultValue("") String mode, @DefaultValue("") String sourceUrl, @DefaultValue("") String sourceUser,
                       @DefaultValue("") String sourcePassword) {
    }
}
