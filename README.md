# micronaut-it-service-request-service

BIAN-aligned Service Domain **it-service-request** (Control Record: `ServiceRequest`, with the `CatalogItem` it is ordered from as a
secondary aggregate), port `8099`.

A person asking IT for something from a **catalog**: "a new laptop", "VPN access", "a software seat". Each catalog item defines the
questions the requester answers, how long fulfilment may take, and whether it needs **approval** first. The second service of the ITSM
core (Journey 12), after incidents.

## What it guarantees, and what it does not

- **A request snapshots its item** (ADR-030): code, name and the fulfilment due date are copied when the request is made, and the
  answers are validated against the item's questions then. Editing or retiring the item never changes a request in flight. An item is
  editable only while `DRAFT`, only a `PUBLISHED` item can be requested, a `RETIRED` one is no longer offered.
- **Self-service without a second API** (ADR-031): a `REQUESTER` sees only the published catalog and their own requests (another's is a
  404), never the internal notes, and cannot do any staff action (403 `ERR-SRQ-00403`), except to **cancel their own** request and to edit and resubmit one that was returned (ADR-035).
- **Approval is delegated** (ADR-032): an item may name a policy on `workflow-approval-service` (which may be a chain of stages). The
  approval is filed first, the request starts `PENDING_APPROVAL`, and the approver's decision is forwarded and read back in the same
  call. Cancelling a waiting request withdraws its approval (best effort).
- **Return for changes** (ADR-035): an approver can send a waiting request back with a comment saying what to fix (`RETURN`). It becomes
  `RETURNED`; its requester edits the answers and resubmits, which starts a NEW approval from stage one. The SLA keeps running.
- **No lost updates, no duplicate codes** (ADR-033): every change is one guarded write that also appends its audit entry; the catalog code
  is unique per organisation, backed by a unique index.
- **SLA is stored as a due date and judged when read** (ADR-034): `PENDING`, `MET` or `BREACHED`, with the clock running from the request
  (waiting for approval counts). No scheduler; a cancelled or rejected request has no SLA.
- **Not here yet:** business-hours calendars, a pause while waiting for the requester, breach alerts (Journey 14), bundles and quotas, events, and external ticketing connectors.

## BIAN Behavior Qualifier Contract

`X-Tenant-Id` is mandatory on every call and scopes it (another tenant's data answers 404); `X-Executor` is mandatory on the actions (a
user id for a REQUESTER); `X-Role` is optional and, with platform security on, comes from the verified token.

### Catalog - `/it-service-request/v1/catalog`

| Behavior Qualifier | Route |
|---|---|
| initiate | `POST /it-service-request/v1/catalog/initiate` `{"code":"LAPTOP","name":"New laptop","category":"HARDWARE","fields":[{"key":"model","label":"Which model?","required":true}],"fulfilmentTargetHours":72,"approvalPolicyId":"<uuid or omit>"}` (staff) |
| retrieve | `GET /it-service-request/v1/catalog/{id}/retrieve` |
| retrieve (collection) | `GET /it-service-request/v1/catalog/retrieve?status=` (a REQUESTER always gets PUBLISHED) |
| update | `PUT /it-service-request/v1/catalog/{id}/update` (DRAFT only) |
| control/publish | `PUT /it-service-request/v1/catalog/{id}/control/publish` (DRAFT -> PUBLISHED) |
| control/retire | `PUT /it-service-request/v1/catalog/{id}/control/retire` (PUBLISHED -> RETIRED, terminal) |
| audit-log/retrieve | `GET /it-service-request/v1/catalog/{id}/audit-log/retrieve` (staff only) |

### Service request - `/it-service-request/v1`

| Behavior Qualifier | Route |
|---|---|
| initiate | `POST /it-service-request/v1/initiate` `{"catalogItemId":"<uuid>","answers":{"model":"X1"},"requesterId":"<uuid, staff only>"}` |
| retrieve | `GET /it-service-request/v1/{id}/retrieve` |
| retrieve (collection) | `GET /it-service-request/v1/retrieve?status=&assigneeId=&catalogItemId=&openOnly=` |
| assignment/update | `PUT /it-service-request/v1/{id}/assignment/update` `{"assigneeId":"<uuid>"}` |
| approval/capture | `PUT /it-service-request/v1/{id}/approval/capture` `{"outcome":"APPROVE","comment":"..."}` (staff; outcome is APPROVE, REJECT or RETURN, and RETURN needs a comment; the approver is `X-Executor`) |
| control/start-fulfilment | `PUT /it-service-request/v1/{id}/control/start-fulfilment` (SUBMITTED or APPROVED -> IN_FULFILMENT) |
| control/fulfil | `PUT /it-service-request/v1/{id}/control/fulfil` `{"notes":"Laptop handed over"}` (IN_FULFILMENT -> FULFILLED) |
| control/close | `PUT /it-service-request/v1/{id}/control/close` (FULFILLED -> CLOSED, terminal) |
| control/resubmit | `PUT /it-service-request/v1/{id}/control/resubmit` `{"answers":{"model":"X2"}}` (RETURNED -> PENDING_APPROVAL, a new approval; the requester or staff) |
| control/cancel | `PUT /it-service-request/v1/{id}/control/cancel` (before it is fulfilled; terminal; a requester may cancel their own) |
| comment/initiate | `POST /it-service-request/v1/{id}/comment/initiate` `{"text":"...","internal":false}` |
| audit-log/retrieve | `GET /it-service-request/v1/{id}/audit-log/retrieve` (staff only) |

```text
SUBMITTED (no approval needed) ------------------+
PENDING_APPROVAL -> APPROVED ---------------------+-> IN_FULFILMENT -> FULFILLED -> CLOSED (terminal)
       |
       +-> REJECTED (terminal)
       +-> RETURNED -> (edit and resubmit) PENDING_APPROVAL again
SUBMITTED | PENDING_APPROVAL | RETURNED | APPROVED | IN_FULFILMENT --cancel--> CANCELLED (terminal)
```

```bash
curl "http://localhost:8099/it-service-request/v1/retrieve?openOnly=true" -H "X-Tenant-Id: <organisationId>" -H "X-Executor: <userId>"
# [{"catalogItemCode":"LAPTOP","status":"IN_FULFILMENT","fulfilment":{"dueAt":"...","state":"PENDING"},...}]
```

Do not put personal data in an answer, a note or a comment: they are stored with the request.

## Configuration

`WORKFLOW_APPROVAL_SERVICE_URL` (default `http://localhost:8090`) is the only cross-service dependency, used only when an item has an
approval policy.

## Error catalog

| Code | HTTP | Meaning |
|---|---|---|
| `ERR-SRQ-00403` | 403 | A REQUESTER tried a staff action other than cancelling their own request (ADR-031, ADR-035) |
| `ERR-SRQ-00404` | 404 | Catalog item or request not found (another tenant's, or another requester's, answers the same) |
| `ERR-SRQ-00409` | 409 | Illegal transition, a duplicate catalog code, an unpublished item requested, or a change lost to a concurrent writer (retry) |
| `ERR-VALIDATION-00400` | 400 | Payload/header/identifier validation failure (bad answers, an unknown question, a missing note...) |
| `ERR-INTERNAL-00500` | 500 | Unexpected technical failure, including workflow-approval being unavailable |

## Architecture decisions

001 hexagonal architecture · 005 UUID identity sovereignty and audit tracing · 013 BIAN conventions · 019 HTTP 409 for state conflicts ·
030 a request snapshots its catalog item · 031 requester self-service scoping · 032 approval delegated to workflow-approval ·
033 guarded writes and unique catalog code · 034 fulfilment SLA computed when read · 035 return for changes and requester cancel.

## License

Licensed under the [PolyForm Strict License 1.0.0](LICENSE): you may read and use this software for noncommercial purposes only. Modifying it, creating derivative works, redistributing it and any commercial use are not permitted without a separate written license. This software is not open source.
