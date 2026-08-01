package com.upi.reconcile.domain;

import org.springframework.stereotype.Component;

/**
 * Pure state-transition logic implementing ARCHITECTURE.md §2.
 * <p>
 * TODO: Implement transition validation and event-driven state changes.
 * This should be a pure function with no DB/IO side effects —
 * takes (currentState, event) and returns the next state or throws.
 */
@Component
public class StateMachine {

    // TODO: Implement state transition logic per §2 transition table
}
