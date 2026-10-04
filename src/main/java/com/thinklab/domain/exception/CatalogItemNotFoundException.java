package com.thinklab.domain.exception;

import java.util.Objects;
import java.util.UUID;

/**
 * Domain Exception: a catalog item could not be resolved for the organisation (another organisation's item, or one a REQUESTER may not
 * see because it is not published, is simply not found).
 *
 * <p>RFC 7807 mapping: HTTP 404 Not Found.
 */
public class CatalogItemNotFoundException extends BusinessException {

    private static final String ERROR_CODE = "ERR-SRQ-00404";

    public CatalogItemNotFoundException(UUID id) {
        super(ERROR_CODE, String.format("Catalog item with sovereign ID [%s] could not be found in the system of record.",
                Objects.requireNonNull(id, "Domain Exception constraint violated: UUID cannot be null.")));
    }
}
