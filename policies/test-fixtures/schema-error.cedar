// IDC-026 negative fixture for policies/validate.sh: Patient has no attribute `consentRevoked` in nabd.cedarschema,
// so `cedar validate` must reject this file. Never loaded by anything.
@id("fixture-unknown-attribute")
forbid (principal, action == Nabd::Action::"readRecord", resource)
when { resource.patient.consentRevoked };
