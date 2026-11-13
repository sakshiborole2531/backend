package com.unichem.inventory.web;

import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/** Uniform RFC 7807 error responses; the "detail" and "message" fields are shown to the user. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static ProblemDetail pd(HttpStatus s, String m) {
        ProblemDetail p = ProblemDetail.forStatus(s);
        p.setDetail(m);
        p.setProperty("success", false);
        p.setProperty("message", m != null ? m : s.getReasonPhrase());
        return p;
    }

    @ExceptionHandler(NotFoundException.class) ProblemDetail nf(NotFoundException e) { return pd(HttpStatus.NOT_FOUND, e.getMessage()); }
    @ExceptionHandler(DuplicateMaterialTypeException.class) ProblemDetail dupMaterialTypeCode(DuplicateMaterialTypeException e) { return pd(HttpStatus.BAD_REQUEST, e.getMessage()); }
    @ExceptionHandler(DuplicateMaterialTypeMappingException.class) ProblemDetail dupMaterialType(DuplicateMaterialTypeMappingException e) { return pd(HttpStatus.BAD_REQUEST, e.getMessage()); }
    @ExceptionHandler(IllegalArgumentException.class) ProblemDetail bad(IllegalArgumentException e) { return pd(HttpStatus.BAD_REQUEST, e.getMessage()); }
    @ExceptionHandler(IllegalStateException.class) ProblemDetail conflict(IllegalStateException e) { return pd(HttpStatus.CONFLICT, e.getMessage()); }
    @ExceptionHandler(AccessDeniedException.class) ProblemDetail denied(AccessDeniedException e) { return pd(HttpStatus.FORBIDDEN, e.getMessage()); }
    @ExceptionHandler({BadCredentialsException.class, LockedException.class, DisabledException.class})
    ProblemDetail auth(RuntimeException e) { return pd(HttpStatus.UNAUTHORIZED, e.getMessage()); }
    @ExceptionHandler({OptimisticLockException.class, ObjectOptimisticLockingFailureException.class})
    ProblemDetail lock(RuntimeException e) { return pd(HttpStatus.CONFLICT, "This record was changed by someone else — reload and try again."); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) {
        String m = e.getMostSpecificCause().getMessage();
        if (m != null && (m.contains("material_type_map_pkey") || m.contains("uq_material_type_code"))) {
            return pd(HttpStatus.BAD_REQUEST, "Duplicate Material Type code found. Material Types must be unique.");
        }
        if (m != null && (m.contains("uq_material_type_composite") || m.contains("material_type_composite")))
            return pd(HttpStatus.BAD_REQUEST, "Duplicate entry found: The combination of Material Type, Dashboard Category, and Business Unit must be unique.");
        if (m != null && m.contains("uq_material_type_cat_bu"))
            return pd(HttpStatus.BAD_REQUEST, "Duplicate entry found: The combination of Material Type, Dashboard Category, and Business Unit must be unique.");
        if (m != null && m.contains("uq_action_open_item")) return pd(HttpStatus.CONFLICT, "This batch already has an open action.");
        if (m != null && m.contains("uq_inventory_key")) return pd(HttpStatus.CONFLICT, "A record with this plant + material + batch already exists.");
        if (m != null && m.contains("uq_business_unit_org_code")) return pd(HttpStatus.CONFLICT, "Business Unit with this name already exists.");
        if (m != null && (m.contains("fk_plant_business_unit_id") || m.contains("plant_bu_code_fkey")))
            return pd(HttpStatus.CONFLICT, "Cannot delete Business Unit because it has associated plants.");
        if (m != null && m.contains("foreign key")) return pd(HttpStatus.CONFLICT, "This record is still referenced by other data.");
        return pd(HttpStatus.CONFLICT, "The change violates a data rule: " + (m == null ? "" : m.split("\n")[0]));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail valid(MethodArgumentNotValidException e) {
        return pd(HttpStatus.BAD_REQUEST, e.getBindingResult().getFieldErrors().stream().map(f -> f.getField() + " " + f.getDefaultMessage()).collect(Collectors.joining("; ")));
    }
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        return pd(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage() != null ? e.getMessage() : "An unexpected server error occurred");
    }
}
