package com.thinklab.domain.exception;

/**
 * Domain Exception: a {@code REQUESTER} tried to do something only staff may do (ADR-031): manage the catalog, assign, decide an
 * approval, work or close a request, or read an audit trail. A requester submits requests, reads their own and comments on them.
 *
 * <p>RFC 7807 mapping: HTTP 403 Forbidden.
 */
public class ServiceRequestAccessDeniedException extends BusinessException {

    private static final String ERROR_CODE = "ERR-SRQ-00403";

    public ServiceRequestAccessDeniedException(String operation) {
        super(ERROR_CODE, "A requester cannot " + operation + ": that is a staff action.");
    }
}
