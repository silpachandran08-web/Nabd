package com.nabd.hms.shared.secrets;

import java.time.Instant;
import java.util.Optional;

/** OPS-058: read access to the secret manager by full path. The cloud adapter (AWS Secrets Manager, read-only IAM role
 * scoped as in infra/terraform/modules/cell/aws/secrets_iam.tf) is wired where the compute story lands; callers use
 * {@link SecretResolver}, never this interface, so every read is path-checked and audited. */
public interface SecretStore {

    Optional<Secret> get(String path);

    /** toString never shows the value. */
    record Secret(String value, Instant lastRotated) {
        @Override
        public String toString() {
            return "Secret[lastRotated=" + lastRotated + "]";
        }
    }
}
