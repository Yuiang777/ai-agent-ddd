# Matter lifecycle and control boundary

The backend exposes `/api/legal/matters` as the business boundary for a real legal-advice matter. The offline profile uses an in-memory adapter only so the workflow can be verified without MySQL, pgvector or a model runtime. The readiness endpoint reports that adapter as non-durable.

## SQL persistence update (2026-10-15)

An optional transactional MySQL adapter is available; see `matter-persistence.md` for configuration and validation limits. The default offline adapter remains non-durable. SQL support has been tested with H2 MySQL mode and still requires target MySQL verification.

## State machine

```text
INTAKE -> CONFLICT_CHECK -> ENGAGEMENT_PENDING -> MATERIAL_PENDING
       -> ANALYSIS -> REVIEW_REQUIRED -> SIGNOFF_REQUIRED
       -> SIGNED_OFF -> CLOSED
```

Every transition carries an actor, role, reason, reference, timestamp and optimistic version. Litigation output cannot become an external report until a lawyer accepts a sign-off task with an opinion and a durable reference.

## Evidence and deadlines

- Evidence is tenant and matter scoped, has an immutable version and a SHA-256 digest, records source/custodian/preservation state, and can be linked to issue identifiers.
- A deadline records its authoritative basis, source reference and timezone. `PROPOSED` deadlines are advisory only; reminders and procedural decisions must use `VERIFIED` deadlines.
- Review decisions, evidence operations and deadline verification emit append-only audit records retrievable from the matter timeline.

## Identity and deployment

Offline requests without identity headers use the fixed `offline-tenant` context. With `APP_ENTERPRISE_SECURITY_CONTEXT_ENABLED=true`, the API requires an authenticated tenant and actor context. Production must provide a transactional tenant-aware `LegalMatterRepository` and object storage adapter before setting `APP_INFRASTRUCTURE_ENABLED=true`; the browser must never be the authority for tenant, model routing or lawyer role.


## Local acceptance update (2026-10-15)

The internal matter API accepts authenticated LAWYER/PARALEGAL principals. Missing or invalid roles are not assigned staff privileges when security context is enabled. Creation and evidence audit events record the authenticated role. This does not provide production member-level ACL or SSO.

Lawyer archive requires a reason and record reference after signoff; CLOSED is terminal in this version. Signed/closed matters freeze evidence and deadline mutations consistently in the UI and API. The current implementation does not enforce different people for authoring, review and signoff.

See the external project design folder's dated acceptance guide for the standalone local bundle, fixed test identity, non-durable memory data, disabled external services and exact validation scope.
