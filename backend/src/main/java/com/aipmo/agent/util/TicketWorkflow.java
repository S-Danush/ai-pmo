package com.aipmo.agent.util;

import com.aipmo.agent.model.Ticket;

/** Shared workflow helpers for metrics, chat, and velocity. */
public final class TicketWorkflow {

    private TicketWorkflow() {}

    public static boolean isDone(Ticket t) {
        return t.getStatus() != null && "Done".equalsIgnoreCase(t.getStatus().trim());
    }
}
