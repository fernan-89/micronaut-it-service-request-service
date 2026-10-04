# ADR-031: Requester Self-Service Scoping

## Status
Accepted

## Context
Service requests are mostly made by the people who need something, not by IT staff. A second "portal API" would double the surface and drift from the staff one. The platform already carries the caller's role in `X-Role` (set by the kit's `SecurityFilter` from the verified token when security is on; client-supplied and trusted only when security is off, like every other header).

## Decision
The same API serves both, narrowed by the `REQUESTER` role:
- A REQUESTER sees only **PUBLISHED** catalog items (a draft or retired item answers 404), and the catalog list is forced to PUBLISHED whatever they ask for.
- A REQUESTER orders for themselves: the requester is the `X-Executor` (a user id), whatever `requesterId` says. Staff must give `requesterId` to order on someone's behalf.
- A REQUESTER sees only their own requests (another person's request answers **404**, not 403, so existence is not leaked), the list is narrowed to them, and **internal comments are never returned** to them. A comment they write is always public, even if they ask for internal.
- A REQUESTER can **cancel their own** request until it is fulfilled (ADR-035; another's is a 404), and edit and resubmit one an approver returned. They cannot do any other staff action (assign, start fulfilment, fulfil, close, decide an approval, read an audit trail, manage the catalog): 403 `ERR-SRQ-00403`.

## Consequences
- Positive: one API, one set of rules; the portal and the staff console share every use case; no existence leak across requesters.
- Negative: the scoping relies on the `X-Role` and `X-Executor` headers being the verified ones, which is true only with platform security on (as for every service). A requester cancelling a request IT already started abandons work in progress (ADR-035).
