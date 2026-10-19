package com.nabd.hms.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoint that throws on demand, to observe how the API renders unhandled failures (S10 QA PLT-027). */
@RestController
public class S10QaBoomController {
    @GetMapping("/v1/s10qa/boom/{kind}")
    public String boom(@PathVariable String kind) throws Exception {
        switch (kind) {
            case "sql": throw new IllegalStateException("ERROR: relation \"patients\" does not exist; SELECT * FROM patients WHERE nric = 'S1234567D'");
            case "npe": throw new NullPointerException("secret-internal-field");
            case "checked": throw new Exception("checked failure with /var/lib/secret/path.java");
            default: return "ok";
        }
    }
}
