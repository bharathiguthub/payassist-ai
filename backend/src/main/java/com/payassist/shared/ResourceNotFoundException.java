package com.payassist.shared;

/** Thrown when a requested resource does not exist; mapped to HTTP 404 Problem Details. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resourceType, String identifier) {
        super(resourceType + " '" + identifier + "' was not found");
    }
}
