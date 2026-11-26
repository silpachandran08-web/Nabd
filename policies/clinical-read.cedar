// IDC-026 spike policy set: 30 policies, PROTOTYPE for the Cedar benchmark only.
// These are NOT approved access rules and nothing in the product loads them; IDC-027/029/030/031 write the real set.
// Shapes follow the rules those stories describe: platform floors, own-patients scope, visibility mode,
// specialty access, break-glass for one patient, delegation.

// ---------- Platform floors (forbid; a facility cannot change them) ----------

@id("floor-suspended-staff")
forbid (principal, action, resource)
when { principal.suspended };

@id("floor-consent-withdrawn-record")
forbid (principal, action in [Nabd::Action::"readRecord", Nabd::Action::"exportRecord"], resource)
when { resource.patient.consentWithdrawn };

@id("floor-consent-withdrawn-patient")
forbid (principal, action == Nabd::Action::"readPatient", resource)
when { resource.consentWithdrawn };

@id("floor-unverified-patient-record")
forbid (principal, action in [Nabd::Action::"readRecord", Nabd::Action::"exportRecord"], resource)
when { !resource.patient.verified };

@id("floor-unverified-patient")
forbid (principal, action == Nabd::Action::"readPatient", resource)
when { !resource.verified };

@id("floor-billing-no-clinical")
forbid (principal in Nabd::Role::"billing", action in [Nabd::Action::"readRecord", Nabd::Action::"exportRecord"], resource);

@id("floor-reception-no-clinical")
forbid (principal in Nabd::Role::"receptionist", action in [Nabd::Action::"readRecord", Nabd::Action::"exportRecord"], resource);

@id("floor-other-facility")
forbid (principal, action in [Nabd::Action::"readRecord", Nabd::Action::"exportRecord"], resource)
when { resource.facility != principal.facility };

@id("floor-sensitive-author-only")
forbid (principal, action in [Nabd::Action::"readRecord", Nabd::Action::"exportRecord"], resource)
when { resource.sensitive && resource.authorOnly && resource.author != principal }
unless { context has breakGlassPatient && context.breakGlassPatient == resource.patient };

@id("floor-sensitive-care-team")
forbid (principal, action in [Nabd::Action::"readRecord", Nabd::Action::"exportRecord"], resource)
when { resource.sensitive && resource.author != principal && !resource.careTeam.contains(principal) }
unless { context has breakGlassPatient && context.breakGlassPatient == resource.patient };

@id("floor-draft-author-only")
forbid (principal, action in [Nabd::Action::"readRecord", Nabd::Action::"exportRecord"], resource)
when { !resource.signed && resource.author != principal };

@id("floor-export-doctor-only")
forbid (principal, action == Nabd::Action::"exportRecord", resource)
unless { principal in Nabd::Role::"doctor" };

@id("floor-delegation-no-sensitive")
forbid (principal, action in [Nabd::Action::"readRecord", Nabd::Action::"exportRecord"], resource)
when { context has delegatedFrom && resource.sensitive };

@id("floor-lab-tech-lab-results-only")
forbid (principal in Nabd::Role::"lab_tech", action in [Nabd::Action::"readRecord", Nabd::Action::"exportRecord"], resource)
when { resource.kind != "lab_result" };

@id("floor-pharmacist-prescriptions-only")
forbid (principal in Nabd::Role::"pharmacist", action in [Nabd::Action::"readRecord", Nabd::Action::"exportRecord"], resource)
when { resource.kind != "prescription" };

// ---------- Facility-configurable restrictions ----------

@id("scope-own-patients-record")
forbid (principal, action in [Nabd::Action::"readRecord", Nabd::Action::"exportRecord"], resource)
when { principal.scope == "own_patients" && !resource.patient.treatedBy.contains(principal) }
unless { context has breakGlassPatient && context.breakGlassPatient == resource.patient };

@id("scope-own-patients-patient")
forbid (principal, action == Nabd::Action::"readPatient", resource)
when { principal.scope == "own_patients" && !resource.treatedBy.contains(principal) }
unless { context has breakGlassPatient && context.breakGlassPatient == resource };

@id("mode-care-team-only")
forbid (principal, action in [Nabd::Action::"readRecord", Nabd::Action::"exportRecord"], resource)
when {
  context.visibilityMode == "CARE_TEAM_ONLY" &&
  resource.author != principal &&
  !resource.careTeam.contains(principal)
}
unless { context has breakGlassPatient && context.breakGlassPatient == resource.patient };

@id("specialty-referral-only")
forbid (principal, action in [Nabd::Action::"readRecord", Nabd::Action::"exportRecord"], resource)
when {
  resource.department.specialtyAccess == "referral_only" &&
  resource.department != principal.department &&
  !resource.careTeam.contains(principal) &&
  !resource.referredTo.contains(principal.department)
}
unless { context has breakGlassPatient && context.breakGlassPatient == resource.patient };

@id("specialty-break-glass-only")
forbid (principal, action in [Nabd::Action::"readRecord", Nabd::Action::"exportRecord"], resource)
when { resource.department.specialtyAccess == "break_glass_only" && !resource.careTeam.contains(principal) }
unless { context has breakGlassPatient && context.breakGlassPatient == resource.patient };

// ---------- Permits ----------

@id("permit-author")
permit (principal, action in [Nabd::Action::"readRecord", Nabd::Action::"exportRecord"], resource)
when { resource.author == principal };

@id("permit-care-team")
permit (principal, action in [Nabd::Action::"readRecord", Nabd::Action::"exportRecord"], resource)
when { resource.careTeam.contains(principal) };

@id("permit-same-department")
permit (principal in Nabd::Role::"clinician", action == Nabd::Action::"readRecord", resource)
when { context.visibilityMode == "SAME_DEPT_PLUS_REFERRED" && resource.department == principal.department };

@id("permit-referred-department")
permit (principal in Nabd::Role::"clinician", action == Nabd::Action::"readRecord", resource)
when { context.visibilityMode == "SAME_DEPT_PLUS_REFERRED" && resource.referredTo.contains(principal.department) };

@id("permit-break-glass")
permit (principal in Nabd::Role::"clinician", action == Nabd::Action::"readRecord", resource)
when { context has breakGlassPatient && context.breakGlassPatient == resource.patient };

@id("permit-delegate")
permit (principal, action == Nabd::Action::"readRecord", resource)
when { context has delegatedFrom && resource.careTeam.contains(context.delegatedFrom) };

@id("permit-pharmacist-prescription")
permit (principal in Nabd::Role::"pharmacist", action == Nabd::Action::"readRecord", resource)
when { resource.kind == "prescription" && resource.signed };

@id("permit-lab-tech-result")
permit (principal in Nabd::Role::"lab_tech", action == Nabd::Action::"readRecord", resource)
when { resource.kind == "lab_result" };

@id("permit-clinician-demographics")
permit (principal in Nabd::Role::"clinician", action == Nabd::Action::"readPatient", resource);

@id("permit-front-desk-demographics")
permit (principal in Nabd::Role::"front_desk", action == Nabd::Action::"readPatient", resource);
