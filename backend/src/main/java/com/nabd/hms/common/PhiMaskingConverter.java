package com.nabd.hms.common;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Logback conversion word "mask" (logback-spring.xml): the formatted message with PHI patterns masked. */
public class PhiMaskingConverter extends MessageConverter {
    @Override
    public String convert(ILoggingEvent event) {
        return PhiMasker.mask(super.convert(event));
    }
}
