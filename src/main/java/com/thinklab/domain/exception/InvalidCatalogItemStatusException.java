package com.thinklab.domain.exception;

/**
 * Domain Exception: an illegal lifecycle step on a catalog item (editing one that is already published, requesting one that is not
 * published), or the item changed while the write was being applied.
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict (ADR-019).
 */
public class InvalidCatalogItemStatusException extends BusinessException {

    private static final String ERROR_CODE = "ERR-SRQ-00409";

    public InvalidCatalogItemStatusException(String message) {
        super(ERROR_CODE, message);
    }
}
