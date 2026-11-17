# Matter SQL persistence

Default offline memory remains non-durable. Select APP_LEGAL_MATTER_STORE=sql with APP_INFRASTRUCTURE_ENABLED=true to use the existing mysqlDataSource. Missing data source or schema fails startup without memory fallback.

Apply db/legal-matter-mysql.sql from infrastructure resources in a backed-up test environment first. APP_LEGAL_MATTER_SCHEMA_INITIALIZE defaults to false; true is an explicit empty-development-database bootstrap option, not a migration engine. Startup checks table and column accessibility. Existing infrastructure configuration also requires its other configured data sources.

Application commands join a single JDBC transaction. Parent matter rows are locked during writes, matter and review updates check versions, and snapshots retain scope, transition history and decisions. Mutating commands do not call a model or wait for humans inside the transaction. Audit append failures roll back associated business writes. The offline memory adapter now serializes commands, returns detached aggregates and rolls back failed commands within the process. It remains non-durable and is intended for local acceptance rather than production scale.

Tables use InnoDB with utf8mb4_bin identity keys. JSON snapshots have explicit versioned representations for matters and reviews. Raw evidence objects are outside this transaction; object storage backup and integrity require separate validation. The adapter does not provide a database migration history, import existing memory data, guarantee exactly-once external execution, or establish complete matter-level authorization.

Verification: 34 related tests passed, including 8 SQL-focused tests using H2 MySQL mode. They cover file database reopen, tenant boundaries, stale and concurrent writes, rollback, decision replay rejection and configuration. A real MySQL server was unavailable; target-engine recovery, locking, migration, constraints and backup restore remain required checks. Full snapshots and unpaged lists are a baseline, not a large-volume query design.
