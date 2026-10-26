# NabdConsumerLagging (OPS-017 / OPS-027)

Ticket severity, not a page. A consumer group (projector, read model, notifier) is more than 60 s behind its stream for 5 min. Staff screens fed by the read model show old data; the delay-broadcast and reminder SLOs are at risk.

1. Grafana `Nabd service health`, "Consumer / read-model lag": which `group` and `stream`, rising or flat?
2. Rising: the consumer is stuck or too slow. Check `consumer_dlq_total` (poison events), the worker logs in Loki for the group name, and Valkey health. A dead letter alert on the same group means a poison event; see docs/architecture/events.md for redrive.
3. Flat but high: a restart finished and the group is catching up; watch it fall.
4. Still lagging after 1 h in clinic hours: raise it to the service owner; lag over 5 min on queue events burns the event-to-screen SLO.
