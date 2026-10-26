# NabdSloSlowBurn (OPS-027)

Page, clinic hours only. The SLO in the `slo` label burns its 28 day budget at more than 6x over 6 h and 30 m (5% of the budget in 6 h). It is slower than NabdSloFastBurn, so there is usually time to find the cause, but it will not fix itself.

1. Same dashboards as `docs/runbooks/NabdSloFastBurn.md`; start from the 6 h range and look for the step where the error ratio or latency changed.
2. Correlate that time with deploys, migrations, config changes, a tenant onboarding or a traffic shift.
3. Mitigate (rollback, scale, disable the offending feature flag) or open a P2 with the finding and owner. If you cannot explain it within 2 h, escalate to the service owner.
4. Check the error budget panel: if remaining budget is under 50% the service is release-frozen.
