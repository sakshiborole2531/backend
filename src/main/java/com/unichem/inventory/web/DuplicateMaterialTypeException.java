package com.unichem.inventory.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a duplicate Material Type code is detected during master configuration validation.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class DuplicateMaterialTypeException extends RuntimeException {
    public DuplicateMaterialTypeException(String message) {
        super(message);
    }
}
