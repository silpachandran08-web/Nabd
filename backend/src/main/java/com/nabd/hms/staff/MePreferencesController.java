package com.nabd.hms.staff;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** FEF-036: the signed-in staff member's own UI preferences. Own row only (the staff id is the token subject); non-PHI, so no audit event. */
@RestController
@RequestMapping("/v1/me/preferences")
public class MePreferencesController {

    static final Set<String> LOCALES = Set.of("en", "hi", "kn", "te", "ta", "ml", "bn", "ar");
    static final Set<String> THEMES = Set.of("system", "dark", "light");
    static final Set<String> DIGITS = Set.of("latn", "arab");

    @Schema(name = "UiPreferences")
    public record Prefs(String uiLocale, String uiTheme, String digitStyle) {}

    @Schema(name = "UiPreferencesRead")
    public record PrefsRead(String uiLocale, String uiTheme, String digitStyle, String clinicDefaultLocale) {}

    @Schema(name = "UiPreferencesWrite")
    public record PrefsWrite(String uiLocale, @NotNull String uiTheme, String digitStyle) {}

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;

    MePreferencesController(JdbcTemplate jdbc, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public PrefsRead get(@AuthenticationPrincipal Jwt jwt) {
        UUID staffId = UUID.fromString(jwt.getSubject());
        UUID tenantId = tenantContext.current();
        String clinic = jdbc.queryForObject("SELECT default_locale FROM tenants WHERE id = ?", String.class, tenantId);
        List<PrefsRead> rows = jdbc.query("SELECT ui_locale, ui_theme, digit_style FROM staff_preferences WHERE staff_id = ?",
                (rs, i) -> new PrefsRead(rs.getString(1), rs.getString(2), rs.getString(3), clinic), staffId);
        return rows.isEmpty() ? new PrefsRead(null, "system", null, clinic) : rows.get(0);
    }

    @PutMapping
    @Transactional
    public Prefs put(@AuthenticationPrincipal Jwt jwt, @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody PrefsWrite req) {
        if (req.uiLocale() != null && !LOCALES.contains(req.uiLocale())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "unsupported-locale", "Unsupported locale", "UNSUPPORTED_LOCALE");
        }
        if (!THEMES.contains(req.uiTheme()) || (req.digitStyle() != null && !DIGITS.contains(req.digitStyle()))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation-failed", "Validation failed", "Unsupported theme or digit style.");
        }
        UUID staffId = UUID.fromString(jwt.getSubject());
        UUID tenantId = tenantContext.current();
        jdbc.update("""
                INSERT INTO staff_preferences (staff_id, tenant_id, ui_locale, ui_theme, digit_style) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (staff_id) DO UPDATE SET ui_locale = EXCLUDED.ui_locale, ui_theme = EXCLUDED.ui_theme, digit_style = EXCLUDED.digit_style""",
                staffId, tenantId, req.uiLocale(), req.uiTheme(), req.digitStyle());
        return new Prefs(req.uiLocale(), req.uiTheme(), req.digitStyle());
    }
}
