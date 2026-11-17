package com.payassist.shared;

/** An entity was asked to move to a state that its lifecycle does not allow from its current state. */
public class InvalidStateTransitionException extends DomainException.Conflict {

    public static final String CODE = "INVALID_STATE_TRANSITION";

    public InvalidStateTransitionException(String resourceType, String resourceRef, Object currentState, Object action) {
        super(CODE, resourceType + " " + resourceRef + " cannot apply " + action + " in state " + currentState);
    }
}
