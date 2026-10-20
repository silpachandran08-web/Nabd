package com.nabd.hms.shared.cache;

/** PLT-052 classification tiers (docs/security/data-classification.md), strictest first. */
public enum DataTier {
    PHI, PII, FINANCIAL, OPERATIONAL
}
