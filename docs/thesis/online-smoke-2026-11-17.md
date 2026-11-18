# Online integration smoke check (2026-11-17)

This is a local engineering check, not a legal QA effectiveness experiment.

- With Docker MySQL, PGVector, Redis and Ollama running, the application started in dev mode with infrastructure and SQL matter storage enabled. A localhost health check returned HTTP 200.
- The configured OpenAI-compatible endpoint listed the chosen model, and a harmless minimal chat-completions request returned a nonempty response. An authenticated localhost POST to `/api/chat` also returned success with nonempty content. Neither request contained a real case or legal source. The test process and temporary admin token were removed afterwards.
- An online startup failure exposed ambiguous constructor injection in `LegalDocumentRepository`. The selected constructor is now annotated, and an offline Spring-context test protects this wiring.
- After the fix, `mvn -q package` produced 47 Surefire XML reports: 209 tests, 0 failures, 0 errors, 3 skipped. `npm test` passed 55 tests and `npm run build` succeeded. Source commit: `6456f559d5e8aeb898abfd31061b2cac019ef6b0`. Re-run these checks and preserve original reports before quoting them in the final thesis.

These checks do not measure citation location, quotation correctness, semantic support or legal answer quality. The three-way paired evaluation, real corpus snapshot and independent human annotation described in `evaluation-protocol.md` remain outstanding. API credentials exist only in the local database, not this repository; the current database column stores them as plaintext. Use managed secret storage and restrict publicly bound Docker ports before production deployment.
