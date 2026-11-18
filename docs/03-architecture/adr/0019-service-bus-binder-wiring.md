# ADR-0019: Service Bus binder wiring in `shp-messaging` (one binder switch, broker-owned retry)

## Status
**Accepted (2026-11-18)**: the Solution Architect accepted the design on 2026-11-18, including the one-switch choice (`SHP_MESSAGING_BINDER`, no separate `servicebus` Spring profile). Task SHR-31; the live Service Bus verification is still pending.
Supplements [ADR-0004](0004-azure-first-portable.md) and [ADR-0015](0015-java-for-all-services.md); completes the
"Service Bus cloud" half of SHR-08, which was marked done with RabbitMQ-only evidence.

## Context
Every Java service publishes and consumes through Spring Cloud Stream. Locally the binder is RabbitMQ (AMQP 0-9-1). In UAT
and prod the bus is Azure Service Bus (AMQP 1.0, Entra ID only, topic `shp-events`, one session-enabled subscription per
consumer, `max_delivery_count = 5`, duplicate detection 10 minutes; `shp-infra` module `servicebus`). The RabbitMQ client
cannot speak AMQP 1.0, so a service that stays on the Rabbit binder cannot reach Service Bus.

Reading `main` before deciding showed the dependency side already exists: `shp-messaging` carries both
`spring-cloud-stream-binder-rabbit` and `spring-cloud-azure-stream-binder-servicebus`, `SHP_MESSAGING_BINDER`
selects the default binder, and the publisher already sets `MessageId` (= event `id`) and `SessionId` (= `subject`). What was
missing, found by comparing the library defaults with the `shp-infra` entities:

1. **Consumer binding defaults for Service Bus.** The subscription is created with `requires_session = true`, but nothing
   set `session-enabled` on the consumer, so a receiver would be refused. `entity-type` was set only for the producer.
2. **Failure semantics.** The Service Bus binder dead-letters a message on the first handler failure unless
   `requeue-rejected = true`; the "5 deliveries then DLQ" rule would not hold.
3. **Retry layers multiplied.** `max-attempts` defaulted to 5 for both binders; on Service Bus that gives up to 25
   handler invocations (5 in process x 5 broker deliveries). The only guard was an environment variable to remember.
4. **Namespace value.** The comment told operators to give the host name; Spring Cloud Azure takes the namespace name
   and appends the domain (the host name logs a "namespace can contain only letters, numbers, and hyphens" warning and
   would build a wrong host).
5. **No test touched the Service Bus binder.**

## Decision
- Keep one switch: `SHP_MESSAGING_BINDER` = `rabbit` (default, local) or `servicebus` (uat/prod). No second
  mechanism (a Spring profile) that could disagree with it. Binder-specific settings are inert for the other binder.
- Complete the defaults in `META-INF/shp/messaging.properties` for Service Bus: consumer `entity-type = topic`,
  `session-enabled = true`, `requeue-rejected = true`.
- Derive in-process attempts from the binder (`rabbit` 5, `servicebus` 1); `SHP_MESSAGING_IN_PROCESS_ATTEMPTS` still overrides. The broker
  owns redelivery and dead-lettering on Service Bus, so 5 deliveries in total.
- Authentication stays Workload Identity (`DefaultAzureCredential`); only `SHP_SERVICEBUS_NAMESPACE` (the namespace name,
  Terraform output `name`) is configuration. No connection string.
- Every service inherits the defaults through `shp-messaging`; a consumer service only declares its own binding
  (`<fn>-in-0`, `destination: shp-events`, `group: <service>`), as `notification` already does.

## Alternatives considered
| Option | Conclusion |
|---|---|
| A `servicebus` Spring profile that flips the binder | Rejected: a second switch next to the existing env var set by `shp-gitops`; two sources of truth. The env var is the contract with gitops |
| Per-service `application-servicebus.yml` | Rejected: duplicated in 6 services, drifts from the library |
| Drop the Rabbit binder from uat/prod images | Rejected for now: both binders stay on the classpath so one image runs everywhere (ADR-0004); cost is image size |
| Azure Service Bus emulator in CI | Deferred: heavier setup, needs SQL Server container; worth a follow-up task for a real delivery test |

## Consequences
- Proven by `MessagingServiceBusProfileTest` (no broker): context starts with the Service Bus binder, the real publisher
  is wired, namespace name resolves to `<name>.servicebus.windows.net`, attempts = 1, consumer and producer binding
  properties resolve to topic / session-enabled / requeue-rejected.
- **Not proven**: sending, receiving, session ordering, duplicate detection, the 5-delivery dead-letter, Entra sign-in and
  the `type` SQL filter. These need the Service Bus emulator or a real namespace; until then SHR-08's Service Bus half and
  the "SHR-08 PARTIAL" entry in `docs/05-plan/dod-audit.md` stay open.
- Out of this repository: `shp-gitops` must set `SHP_MESSAGING_BINDER=servicebus` and `SHP_SERVICEBUS_NAMESPACE`
  for uat/prod, and `shp-infra` must grant each service the Sender/Receiver roles (already in the module). Not changed here.
