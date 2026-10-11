package com.nabd.hms.patient.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Body of PATCH /v1/patients/{id} — true partial-update semantics. A property that is absent from
 * the JSON is left unchanged; a property present with a JSON null is an explicit clear (only valid
 * for guardianId, address and nationalId — name/phone/dob/gender are mandatory so null is a 400).
 * Jackson calls a setter only for properties present in the payload, which is how absent and null
 * are told apart (a record cannot do that). Unknown properties (e.g. confirmedNotDuplicate from an
 * older client that re-sends the whole register payload) are ignored.
 */
public final class PatientPatchRequest {

    @Pattern(regexp = ".*\\S.*", message = "must not be blank")
    private String name;
    @Pattern(regexp = ".*\\S.*", message = "must not be blank")
    private String phone;
    @Past
    private LocalDate dob;
    @Pattern(regexp = "male|female|other")
    private String gender;
    private UUID guardianId;
    private String address;
    private String nationalId;

    private final Set<String> present = new HashSet<>();

    @JsonSetter("name") public void setName(String v) { name = v; present.add("name"); }
    @JsonSetter("phone") public void setPhone(String v) { phone = v; present.add("phone"); }
    @JsonSetter("dob") public void setDob(LocalDate v) { dob = v; present.add("dob"); }
    @JsonSetter("gender") public void setGender(String v) { gender = v; present.add("gender"); }
    @JsonSetter("guardianId") public void setGuardianId(UUID v) { guardianId = v; present.add("guardianId"); }
    @JsonSetter("address") public void setAddress(String v) { address = v; present.add("address"); }
    @JsonSetter("nationalId") public void setNationalId(String v) { nationalId = v; present.add("nationalId"); }

    public boolean has(String property) { return present.contains(property); }

    public String name() { return name; }
    public String phone() { return phone; }
    public LocalDate dob() { return dob; }
    public String gender() { return gender; }
    public UUID guardianId() { return guardianId; }
    public String address() { return address; }
    public String nationalId() { return nationalId; }
}
