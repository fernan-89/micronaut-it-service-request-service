# ADR-034: The Fulfilment SLA Is Computed When Read

## Status
Accepted

## Context
Each catalog item has a fulfilment target in hours. Whether a request met or breached it could be maintained by a scheduler that flips a flag, but that is a second moving part that can fall behind, double-fire or disagree with the data.

## Decision
- The request stores one fact: the **due date** (`createdAt + target`, computed when the request is made, from the item's target at that moment - ADR-030). Whether it was met is answered **when the request is read**: `PENDING` (not done, not late), `MET` (done on time), `BREACHED` (late, whether it is still running or it finished late).
- The clock starts at the request, so **time spent waiting for approval counts**: the requester experiences one wait, and an approver who sits on a request should be visible as a breach.
- A `CANCELLED` or `REJECTED` request has no SLA (nothing is owed). A FULFILLED or CLOSED one is judged by when it was fulfilled.
- No scheduler, no flag, no alert: breach notification belongs to a later journey (alerting) and will read the same due dates.

## Consequences
- Positive: nothing to fall behind; the same data always gives the same answer; works unchanged for old requests.
- Negative: no business-hours calendar (the target is wall-clock hours), no pause while waiting for the requester, and the list cannot be filtered by "breached" in the database (it is derived per response).
