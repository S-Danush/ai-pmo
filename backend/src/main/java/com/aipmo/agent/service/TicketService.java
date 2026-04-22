package com.aipmo.agent.service;

import com.aipmo.agent.dto.DataQuality;
import com.aipmo.agent.model.Ticket;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
public class TicketService {

    public List<Ticket> getMockTickets() {
        List<Ticket> tickets = new ArrayList<>();
        Instant u = Instant.now();
        // MEDIUM: stuck only (PR near batch avg)
        tickets.add(
                baseTicket("ABC-101", "Fix flaky integration test in CI", "QA", 36, 14, 2, 0, u, 1));
        // GREEN path: healthy
        tickets.add(
                baseTicket("ABC-102", "Add loading spinner to dashboard", "Dev", 10, 8, 1, 0, u, 2));
        // HIGH: critical stuck + PR delay likely when avg computed
        tickets.add(
                baseTicket("ABC-103", "Urgent: payment webhook retries", "QA", 52, 22, 3, 1, u, 0));
        // PR delay vs peers + LOW bounce-only candidate
        tickets.add(
                baseTicket(
                        "ABC-104",
                        "Refactor order service (security review)",
                        "Code Review",
                        8,
                        24,
                        5,
                        1,
                        u,
                        3));
        // BOUNCING only
        tickets.add(
                baseTicket("ABC-105", "Clarify requirements for feature X", "Dev", 6, 10, 6, 4, u, 4));
        return tickets;
    }

    public List<Ticket> getSimulationTickets() {
        List<Ticket> tickets = new ArrayList<>();
        Instant u = Instant.now();
        tickets.add(
                baseTicket(
                        "SIM-CRITICAL", "P0: checkout timeout in prod", "QA", 72, 18, 3, 1, u, 0));
        tickets.add(
                baseTicket(
                        "SIM-PRBOT", "PR pending review: auth middleware", "Code Review", 14, 58, 2, 0, u, 1));
        tickets.add(
                baseTicket("SIM-BOUNCE", "Rework API contract", "Dev", 12, 11, 9, 5, u, 2));
        tickets.add(
                baseTicket("SIM-WATCH", "Stabilize nightly build", "QA", 32, 16, 2, 0, u, 1));
        tickets.add(
                baseTicket("SIM-HEALTHY", "Add unit tests for util", "Dev", 6, 9, 1, 0, u, 0));
        tickets.add(
                baseTicket(
                        "SIM-UNASS", "Triage backlog item (no owner)", "To Do", 48, 0, 1, 0, u, 5, true));
        return tickets;
    }

    private static Ticket baseTicket(
            String id,
            String summary,
            String status,
            int timeInState,
            int prTime,
            int statusChanges,
            int pingPongTransitions,
            Instant now,
            int daysAgoUpd) {
        return baseTicket(
                id, summary, status, timeInState, prTime, statusChanges, pingPongTransitions, now, daysAgoUpd, false);
    }

    private static Ticket baseTicket(
            String id,
            String summary,
            String status,
            int timeInState,
            int prTime,
            int statusChanges,
            int pingPongTransitions,
            Instant now,
            int daysAgoUpd,
            boolean unassigned) {
        Instant created = now.minus(60, ChronoUnit.DAYS);
        Instant jiraUpd = now.minus(daysAgoUpd, ChronoUnit.DAYS);
        return Ticket.builder()
                .id(id)
                .summary(summary)
                .status(status)
                .statusCategory(mapCategory(status))
                .createdAt(created)
                .jiraUpdatedAt(jiraUpd)
                .assignee(unassigned ? "Unassigned" : "Dev Person")
                .timeInState(timeInState)
                .prTime(prTime)
                .statusChanges(statusChanges)
                .pingPongTransitions(pingPongTransitions)
                .flags(new ArrayList<>())
                .dataQuality(DataQuality.MOCK)
                .jiraDataAvailable(true)
                .prDataAvailable(false)
                .build();
    }

    private static String mapCategory(String s) {
        if (s == null) {
            return "";
        }
        if (s.equals("Dev") || s.equals("Code Review") || s.equals("QA")) {
            return "In Progress";
        }
        if (s.equals("To Do")) {
            return "To Do";
        }
        return "In Progress";
    }
}
