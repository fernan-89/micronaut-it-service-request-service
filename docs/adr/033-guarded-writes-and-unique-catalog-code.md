# ADR-033: Every Change Is a Guarded Write, and the Catalog Code Is Unique per Organisation

## Status
Accepted

## Context
Two people working the same request (or the same catalog item) could silently overwrite each other. Separately, the catalog code ("LAPTOP") is how people and integrations name an item, so two items with the same code in one organisation would be ambiguous - and a check-then-insert in the use case is racy.

## Decision
- **Every change is a single guarded write.** The repository saves the state the aggregate reached together with its audit entry in one atomic update, and only while the aggregate still has the status it had when it was loaded. If someone else moved it in between, the write matches nothing and the caller gets 409 (`ERR-SRQ-00409`; read again and retry) - never a lost update. Comments are an atomic `$push` and need no guard. Both the request and the catalog item work this way.
- **The code is unique per organisation, enforced by the database.** The use case looks the code up first (case-insensitively: codes are stored upper-cased) to give a clean 409, but the **unique index `(organisationId, code)`** is the atomic backstop: the loser of a race gets a duplicate-key error that the adapter maps to the same `DuplicateCatalogItemException`. Another organisation may reuse the code.
- Indexes serve the real queries: `(organisationId, status)` on both collections, `(organisationId, requesterId)`, `(organisationId, assigneeId)` and `(organisationId, catalogItemId)` on requests. They are created at startup, fail-open and idempotent, and can be turned off with `thinklab.mongo.create-indexes=false`.

## Consequences
- Positive: no lost updates, no partial writes, no duplicate codes even under a race.
- Negative: the guard is on the status, so two edits that do not change it (two `assignment/update` calls at the same instant) are last-writer-wins, which is acceptable for a field meant to be overwritten. A retired item keeps its code, so the code cannot be reused in that organisation.
