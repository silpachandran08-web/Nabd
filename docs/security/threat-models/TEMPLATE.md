# Threat model: <EPxx> <name>

Status: Draft | Reviewed (security-privacy) | Approved (DPO, for LINDDUN). Recorded before the first build sprint (T-077).
Scope: services, stories and tracker tasks in scope. Classification tiers per [data-classification.md](../data-classification.md).

## Assets and data
| Asset | Tier | Where it lives |
|---|---|---|

## Trust boundaries and flows
Numbered flows across each boundary (actor -> component -> store), one line each.

## STRIDE
One row per threat. Every mitigation names an existing tracker task (`T-nnn`, from the tracker in specs.json) or says
**gap: no tracker task** so the gap is visible. Never invent task ids.

| # | Category | Threat | Mitigation | Task |
|---|---|---|---|---|
| S1 | Spoofing | | | |
| T1 | Tampering | | | |
| R1 | Repudiation | | | |
| I1 | Information disclosure | | | |
| D1 | Denial of service | | | |
| E1 | Elevation of privilege | | | |

## LINDDUN (privacy)
| Category | Threat | Mitigation | Task |
|---|---|---|---|
| Linkability | | | |
| Identifiability | | | |
| Non-repudiation (privacy) | | | |
| Detectability | | | |
| Disclosure of information | | | |
| Unawareness | | | |
| Non-compliance | | | |

## Open items
Gaps, assumptions and decisions needed (DPO, clinical, external).
