package com.thinklab.domain.exception;

/**
 * Domain Exception: a catalog item code is already taken within the organisation (the use case checks first and a unique
 * {@code (organisationId, code)} index is the atomic backstop for two concurrent creations, ADR-033).
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict.
 */
public class DuplicateCatalogItemException extends BusinessException {

    private static final String ERROR_CODE = "ERR-SRQ-00409";

    public DuplicateCatalogItemException(String code) {
        super(ERROR_CODE, String.format("A catalog item with code [%s] already exists in this organisation.", code));
    }
}
