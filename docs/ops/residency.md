# PHI residency proof (OPS-057)

`infra/residency/sinks.yaml` lists every place patient data can flow to (database, Valkey, object storage, backups, logs, traces, metrics, CDN origin, email, WhatsApp, SMS, LLM inference) per cell, with its class: `in_country` (host must match the cell patterns; AWS regions come from `modules/cell/residency.tf`) or `approved_processor` (processor, contract file, `phi_in_payload: false`, and a sink that holds PHI can never be one).

`python3 infra/residency/check_egress.py --cell in` also scans the repo config (OTel exporters, `application*.yml`, Java sources, prod-in manifests) and fails naming any endpoint that is not inventoried. It runs on every infra/config change (`.github/workflows/residency.yml`) and nightly against prod-in (`residency-nightly.yml`: report kept as an artifact, a failure opens an `S1`/`privacy-defect` issue).

Adding an exporter or SDK: add the host to `sinks.yaml` first (in-country, or an approved processor with a contract), and to `infra/prod-in/egress-policy.yaml`; the check fails if the two lists differ. WhatsApp (Meta) is the one approved out-of-country processor and carries ids and utility templates only.

Needs external action: apply `egress-policy.yaml` (Cilium) to staging-in/prod-in, run `infra/residency/blocked-egress-probe.sh` there, set the `PROD_IN_KUBECONFIG` secret and the `S1`/`privacy-defect` issue labels so the nightly can read live endpoints and open defects.
