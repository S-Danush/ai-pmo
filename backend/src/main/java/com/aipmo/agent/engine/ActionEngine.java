package com.aipmo.agent.engine;

import com.aipmo.agent.dto.RootCauseDto;
import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.service.MetricsService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** Maps root-cause signals to a recommended next action and owner. */
@Component
public class ActionEngine {

    public record ActionPlan(String recommendedAction, String actionOwner) {}

    public ActionPlan decide(Ticket ticket, RootCauseDto rootCause) {
        List<String> flags = ticket.getFlags() != null ? ticket.getFlags() : List.of();
        String ps = ticket.getPrStatus() != null ? ticket.getPrStatus().trim().toUpperCase(Locale.ROOT) : "";
        String dep = ticket.getDependency() != null ? ticket.getDependency().trim().toUpperCase(Locale.ROOT) : "NONE";
        int bounce = Math.max(ticket.getBounceCount(), ticket.getPingPongTransitions());

        if (flags.contains(MetricsService.FLAG_PR_DELAY) || "OPEN".equals(ps)) {
            return new ActionPlan(
                    "Follow up with the reviewer or reassign a secondary reviewer so this PR can land today.",
                    "Reviewer / tech lead");
        }
        if (flags.contains(MetricsService.FLAG_PR_NOT_CREATED) || flags.contains(MetricsService.FLAG_DEV_NOT_STARTED)) {
            return new ActionPlan(
                    "Confirm whether development has started; if yes, open or link the PR and post the next concrete milestone.",
                    "Assignee");
        }
        if (flags.contains(MetricsService.FLAG_BLOCKED) && !"NONE".equals(dep)) {
            return new ActionPlan(
                    "Escalate the dependency to the owning API or partner team with a dated ask and link the ticket + PR thread.",
                    "External team / API owner");
        }
        if (flags.contains(MetricsService.FLAG_BLOCKED)) {
            return new ActionPlan(
                    "Document the true blocker in the ticket and tag the person who can unblock — avoid silent “blocked” states.",
                    "Assignee / EM");
        }
        if (flags.contains(MetricsService.FLAG_BOUNCING) || bounce >= 3) {
            return new ActionPlan(
                    "Clarify acceptance criteria for the current stage with PM + QA in one short thread, then freeze scope for this slice.",
                    "Assignee + PM + QA");
        }
        if (flags.contains(MetricsService.FLAG_DEPENDENCY_RISK) && !"NONE".equals(dep)) {
            return new ActionPlan(
                    "Schedule a 15m checkpoint with the dependency owner and mirror outcomes back to Jira so dates stay honest.",
                    "Dependency owner");
        }
        if (flags.contains(MetricsService.FLAG_MERGED_NOT_DEPLOYED)) {
            return new ActionPlan(
                    "Align with release management on the next promotion window — merged code still needs an environment slot.",
                    "Release / DevOps");
        }

        if (rootCause != null && rootCause.getReasons() != null && !rootCause.getReasons().isEmpty()) {
            String r0 = rootCause.getReasons().get(0).toLowerCase(Locale.ROOT);
            if (r0.contains("120+") || r0.contains("dwell")) {
                return new ActionPlan(
                        "Post a one-line update on what is blocking forward motion and who owns the next touch today.",
                        "Assignee");
            }
        }
        return new ActionPlan(
                "Confirm the next owner and date for the single next action so the card does not age without narrative.",
                "Assignee");
    }
}
