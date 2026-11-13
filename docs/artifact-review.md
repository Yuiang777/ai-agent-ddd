# Version-bound artifact review

Publish content with POST /api/legal/matters/{id}/artifacts using content, workflowRunId and expectedVersion. The server computes a SHA-256 digest and appends a report version. Publishing requires ANALYSIS, REVIEW_REQUIRED or SIGNOFF_REQUIRED. Changed content returns the matter to ANALYSIS.

Review submission now requires an artifact and an explicit expected matter version. Submit QUALITY_REVIEW from ANALYSIS, decide it, then submit LAWYER_SIGNOFF from SIGNOFF_REQUIRED. Signoff needs a completed quality review for the same report and registered evidence fingerprint. A signoff task stays in SIGNOFF_REQUIRED until decided. The existing decision endpoint is retained.

Old unbound tasks cannot approve reports. Generic transitions cannot directly set SIGNOFF_REQUIRED or SIGNED_OFF. Report revisions and evidence updates make old bindings unusable; historical decisions remain stored. Signed or closed matters reject report and evidence changes pending a future amendment design.

Snapshots write schemaVersion 2 and read versions 1 and 2. No SQL DDL change is required. Older binaries must not be assumed able to read version 2 snapshots.

The API returns report history and review bindings. The Matters page now provides report publishing, version selection, full-text side-by-side comparison and review actions; authenticated end-to-end browser verification remains pending. Evidence fingerprinting observes registered evidence versions and hashes at review submission, not the actual input provenance of the model run or externally changed raw files. Model input manifests, source-law version changes, external delivery controls remain future work.


## Independent review (2026-10-16)

Starting or deciding a bound task rejects the report publisher (`createdBy`) with ARTIFACT_AUTHOR_CANNOT_REVIEW. A completed quality reviewer for the same report and evidence binding cannot sign it (QUALITY_REVIEWER_CANNOT_SIGNOFF). An in-progress task can only be decided by its reviewer (REVIEW_ASSIGNEE_MISMATCH). These checks also cover returned decisions and run before task mutation. Old unbound or stale tasks cannot be started.

The Matters UI disables blocked actions and explains the reason using the authenticated actor context. Author means the authenticated report publisher; this does not establish the provenance of imported or model-generated text. Matter ownership alone does not prohibit independent review. No new database fields or snapshot schema are needed.

Backend Maven tests and frontend unit tests/build passed on 2026-10-16. The existing browser acceptance harness uses a single fixed actor; its old self-review walkthrough must be converted to distinct authenticated author/reviewer/signer identities before a new acceptance bundle can be certified. Previous browser evidence does not validate this change.


Follow-up: the loopback acceptance proxy now offers fixed author/reviewer/signer identities with HttpOnly SameSite cookies. The three-person browser walkthrough and proxy header/origin checks passed on 2026-10-16. The UI failure simulation suite also passed; simulated model responses are not legal-output validation. Production authentication and matter membership remain separate work.
