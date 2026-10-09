# ADR 0001: Durable ownership of an uncertain transfer

Status: accepted, 9 October 2026.

## Context

A lost response does not establish whether a bank accepted a transfer. Recreating a new request after a timeout can debit an account twice. A ViewModel flag or an in-memory map cannot protect this boundary through process death.

## Decision

Use three modules: pure Kotlin domain rules and ports, Android data storage/transports, and a Compose application. Constructor injection and one application container keep the sample easy to inspect.

The client Room database owns an immutable request journal, an active request slot, and cached account/history projections. The separate synthetic gateway Room database owns account balances and accepted operations. Gateway acceptance and client receipt cannot share a transaction. This makes the timeout demonstration exercise a real loss-of-response boundary.

On confirmation the client persists the request and payer-scoped key before dispatch. An unresolved request blocks its replacement. Automatic read retries from Katty are deliberately not reused for payment submission. Safe retry first queries the original key, then can resend the exact original request with that key. A changed payload using that key conflicts.

Local Prepared, Submitting and Unknown describe what the client knows. Pending, Succeeded and Rejected are authoritative outcomes. A timeout, cancellation, malformed response, server failure or unresolved lookup never becomes a rejection. Pending remains active until status lookup resolves it. Terminal outcomes cannot regress. Only terminal outcomes release the active slot.

The gateway atomically checks funds, records the operation and debits or reserves once. History uses unique operation IDs. Money uses checked integer cents, with no binary floating point parsing or rounding.

## Lessons reused from Katty

Reuse its verified dependency matrix, wrapper checksum, lifecycle-aware state collection, semantic UI tags, Room schema export and executable verification. Explicit data ownership, transaction boundaries and process-recreation tests address the class of persistence bugs repaired in Katty. The app does not inherit Katty's module count, cat models, annotation sync flags or telemetry placeholders.

## Consequences and scope

Two local databases model two owners, not a real remote trust boundary. Both live on the same device and may be removed by clearing app data. The default app uses synthetic accounts, names and IBAN fixtures; no money moves. The HTTP adapter documents and tests a possible remote contract, but no real bank is integrated. This is an engineering portfolio demonstration, not a production banking implementation.

Durable reopen, replay, concurrency, cancellation, pending and late-result regressions establish the guarantees claimed by the sample. Executed evidence belongs in docs/verification.md.
