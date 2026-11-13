package com.unichem.inventory.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a Material Type mapping has a duplicate combination of Dashboard Category and Business Unit.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class DuplicateMaterialTypeMappingException extends RuntimeException {
    public DuplicateMaterialTypeMappingException(String message) {
        super(message);
    }
}
