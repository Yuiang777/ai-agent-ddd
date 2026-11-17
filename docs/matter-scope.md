# Matter intake scope

POST /api/legal/matters accepts optional scope with businessType, relatedJurisdictions and workingLanguages. GET returns scope alongside existing matter data.

These fields record intake references only. They do not determine applicable law or certify supported jurisdictions. Business and jurisdiction references are normalized uppercase identifiers; language tags are normalized separately.

Each list is limited to 20 values, each reference to 64 characters. Values are deduplicated. Legacy requests retain unspecified scope and must supply the existing required title and clientReference.

The matterScope. attribute namespace is reserved. Owner identity comes from the authenticated actor, not request attributes. Scope maps to existing attributes as a transitional representation.

The default memory store remains non-durable. An optional SQL adapter now persists scope with matter snapshots; see matter-persistence.md. Scope editing, coverage validation, non-litigation lifecycles and durable approval checkpoints remain future work.
