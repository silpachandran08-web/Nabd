package com.nabd.hms.staff.presets;

import com.nabd.hms.common.ModuleGrant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PLT-016: the six preset role bundles every new clinic starts with, versioned in code.
 * Flags read like the RBAC Matrix sheet, in its column order: view, create, edit, delete/void, approve,
 * refund/discount, export. Y and P (plan-dependent: gated by plan/pharmacy mode, not the role) grant; N and - do not.
 * The ten matrix modules are transcribed verbatim (golden file: src/test/resources/rbac-matrix-golden.csv),
 * except MF-52: the Owner preset has no clinical:approve, only Owner-doctor (Owner plus Doctor plus clinical:approve) does.
 * nursing, specialty_dental and departments are not in the matrix; their rows are marked "extension" in the golden file.
 * Bump VERSION whenever a definition changes; reset-to-preset then moves a role to the new definition.
 */
public final class RolePresets {

    public static final int VERSION = 1;

    public record Preset(String code, String name, boolean builtIn, List<ModuleGrant> grants) {
    }

    private static final Preset OWNER = new Preset("owner", "Owner", true, List.of(
            g("patients", "YYYYY-Y"), g("queue", "YYYYY-Y"), g("clinical", "YNNYN-Y", true),
            g("billing", "YYYYYYY"), g("packages", "YYYYYYY"), g("pharmacy", "PPPPPPP"), g("reports", "Y---Y-Y"),
            g("staff", "YYYYY-Y"), g("setup", "Y-Y---Y"), g("compliance", "Y--YY-Y"),
            g("nursing", "YYYYYYY"), g("specialty_dental", "YYYYYYY"), g("departments", "YYYYYYY")));

    private static final Preset DOCTOR = new Preset("doctor", "Doctor", false, List.of(
            g("patients", "YYY-N--"), g("queue", "Y-Y----"), g("clinical", "YYYYN--"),
            g("billing", "Y------"), g("packages", "YYY----"), g("pharmacy", "PPN----"), g("reports", "Y---N--"),
            g("staff", "N------"), g("setup", "N------"), g("compliance", "N------"),
            g("nursing", "YY-----"), g("specialty_dental", "YYY----"), g("departments", "Y------")));

    private static final Preset NURSE = new Preset("nurse", "Nurse", false, List.of(
            g("patients", "YNY-N--"), g("queue", "Y-Y----"), g("clinical", "YYY-N--"),
            g("billing", "N------"), g("packages", "Y-Y----"), g("pharmacy", "PNN----"), g("reports", "N------"),
            g("staff", "N------"), g("setup", "N------"), g("compliance", "N------"),
            g("nursing", "YYY----"), g("specialty_dental", "Y------"), g("departments", "Y------")));

    private static final Preset RECEPTIONIST = new Preset("receptionist", "Receptionist", false, List.of(
            g("patients", "YYY-N--"), g("queue", "YYY----"), g("clinical", "N------"),
            g("billing", "YYYNYN-"), g("packages", "YYN----"), g("pharmacy", "PPN----"), g("reports", "N------"),
            g("staff", "N------"), g("setup", "N------"), g("compliance", "N------"),
            g("nursing", "N------"), g("specialty_dental", "N------"), g("departments", "Y------")));

    private static final Preset FRONTDESK_VITALS = new Preset("frontdesk_vitals", "Front-desk + Vitals", false, List.of(
            g("patients", "YYY-N--"), g("queue", "YYY----"), g("clinical", "YYN-N--"),
            g("billing", "YYYNYN-"), g("packages", "YYN----"), g("pharmacy", "PPN----"), g("reports", "N------"),
            g("staff", "N------"), g("setup", "N------"), g("compliance", "N------"),
            g("nursing", "N------"), g("specialty_dental", "N------"), g("departments", "Y------")));

    /** MF-52: an owner who is also a clinician — Owner and Doctor combined, plus clinical:approve. */
    private static final Preset OWNER_DOCTOR = new Preset("owner_doctor", "Owner-doctor", false, ownerDoctorGrants());

    public static final List<Preset> ALL = List.of(OWNER, OWNER_DOCTOR, DOCTOR, NURSE, RECEPTIONIST, FRONTDESK_VITALS);

    private RolePresets() {
    }

    public static Optional<Preset> byCode(String code) {
        return ALL.stream().filter(p -> p.code().equals(code)).findFirst();
    }

    private static List<ModuleGrant> ownerDoctorGrants() {
        Map<String, ModuleGrant> doctor = new LinkedHashMap<>();
        DOCTOR.grants().forEach(d -> doctor.put(d.module(), d));
        List<ModuleGrant> out = new ArrayList<>();
        for (ModuleGrant o : OWNER.grants()) {
            ModuleGrant d = doctor.get(o.module());
            boolean approve = o.approve() || d.approve() || "clinical".equals(o.module());
            out.add(new ModuleGrant(o.module(), o.view() || d.view(), o.create() || d.create(), o.edit() || d.edit(),
                    o.delete() || d.delete(), approve, o.refundDiscount() || d.refundDiscount(),
                    o.export() || d.export(), o.breakGlass() || d.breakGlass()));
        }
        return List.copyOf(out);
    }

    private static ModuleGrant g(String module, String flags) {
        return g(module, flags, false);
    }

    /** breakGlass is not a matrix column; only the Owner keeps it, as before PLT-016, so it can still hand it out. */
    private static ModuleGrant g(String module, String flags, boolean breakGlass) {
        if (flags.length() != 7) {
            throw new IllegalArgumentException("7 flags expected for " + module);
        }
        boolean[] f = new boolean[7];
        for (int i = 0; i < 7; i++) {
            char c = flags.charAt(i);
            if ("YPN-".indexOf(c) < 0) {
                throw new IllegalArgumentException("bad flag " + c + " for " + module);
            }
            f[i] = c == 'Y' || c == 'P';
        }
        return new ModuleGrant(module, f[0], f[1], f[2], f[3], f[4], f[5], f[6], breakGlass);
    }
}
