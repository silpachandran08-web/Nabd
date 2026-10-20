// OPS-046: prints the Argon2 hash the API's PIN check accepts (same parameters as SecurityConfig.passwordEncoder),
// so seed.sql can create synthetic staff. The PIN comes from the caller's environment and is never stored.
// perf/run.sh --seed runs it (classpath: spring-security-crypto, bcprov-jdk18on, spring-jcl from ~/.m2).
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

public class PinHash {
    public static void main(String[] args) {
        System.out.print(new Argon2PasswordEncoder(16, 32, 1, 19456, 2).encode(args[0]));
    }
}
