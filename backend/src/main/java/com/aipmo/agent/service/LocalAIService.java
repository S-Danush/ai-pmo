package com.aipmo.agent.service;

import com.aipmo.agent.dto.AiInsightOutcome;
import com.aipmo.agent.dto.TicketInsightPayload;
import com.aipmo.agent.logging.PipelineMdc;
import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.util.TicketDisplayMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Rule-based “AI” insights for evaluation: no external models, plain language, tied to ticket facts.
 */
@Service
public class LocalAIService {

    private static final Logger log = LoggerFactory.getLogger(LocalAIService.class);

    private static final int STUCK_HOURS = 48;
    private static final int PR_SLOW_HOURS = 32;
    private static final int LONG_IN_PROGRESS_HOURS = 48;
    private static final int HIGH_PRIORITY_DWELL_HOURS = 72;
    /** “Over 5 days” style narratives (hours in current status). */
    private static final int FIVE_DAYS_IN_STATE_HOURS = 120;

    private static final String CAT_EXECUTION_DELAY = "Execution delay";
    private static final String CAT_REVIEW_BOTTLENECK = "Review bottleneck";
    private static final String CAT_DEPENDENCY_BLOCKER = "Dependency blocker";
    private static final String CAT_OWNERSHIP_GAP = "Ownership gap";
    private static final String CAT_SCOPE_AMBIGUITY = "Scope ambiguity";

    /**
     * Human, manager-style Teams copy: greeting, observation, reasoning (why it matters), action,
     * closing impact. Uses ticket facts, flags, and light randomization so it does not read as a
     * static template.
     */
    public String generateManagerStyleMessage(Ticket ticket) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String assignee = ticket.getAssignee();
        String who = firstNameForGreeting(assignee);
        String id = ticket.getId() != null ? ticket.getId() : "this item";
        String friendlyStatus =
                ticket.getDisplayStatus() != null
                        ? ticket.getDisplayStatus()
                        : TicketDisplayMapper.toFriendlyStatus(ticket.getStatus());
        int hours = ticket.getTimeInState();
        int pr = ticket.getPrTime();
        List<String> flags = ticket.getFlags() != null ? ticket.getFlags() : List.of();
        String priority = ticket.getPriority() != null ? ticket.getPriority() : "Medium";
        String bottleneck = ticket.getBottleneckCategory();

        StringBuilder out = new StringBuilder(900);
        out.append(buildGreetingLine(r, who, id)).append("\n\n");

        out.append(buildObservationParagraph(r, ticket, friendlyStatus, hours, pr, flags, priority)).append("\n\n");

        out.append(buildReasoningParagraph(r, ticket, friendlyStatus, hours, pr, flags, priority)).append("\n\n");

        out.append(buildActionParagraph(r, ticket, friendlyStatus, hours, pr, flags, priority, bottleneck));

        String closingImpact = buildClosingImpactLine(r, ticket, priority, hours, friendlyStatus, flags);
        if (closingImpact != null) {
            out.append("\n\n").append(closingImpact);
        }

        String message = out.toString().trim();
        log.info("Manager-style Teams message built ticketId={}", ticket.getId());
        return message;
    }

    private static String firstNameForGreeting(String assignee) {
        if (assignee == null || assignee.isBlank() || "unassigned".equalsIgnoreCase(assignee.trim())) {
            return null;
        }
        if ("Unassigned".equalsIgnoreCase(assignee.trim())) {
            return null;
        }
        String t = assignee.trim();
        int sp = t.indexOf(' ');
        return sp > 0 ? t.substring(0, sp) : t;
    }

    private static String buildGreetingLine(ThreadLocalRandom r, String firstName, String ticketId) {
        String namePart = firstName != null ? firstName : "there";
        return pickN(
                r,
                "Hey " + namePart + " — quick check on " + ticketId + ".",
                "Quick check on " + ticketId + (firstName != null ? " — thanks " + namePart : "") + ".",
                "Hey " + namePart + ", can you take a look at " + ticketId + " when you have a moment?",
                "Just wanted to check in on " + ticketId + " with you" + (firstName != null ? ", " + firstName : "") + ".",
                "Taking a look at " + ticketId + (firstName != null ? " for you, " + firstName : "") + " — quick pulse check.",
                "Hi " + namePart + " — looping in on " + ticketId + " so nothing slips between the cracks.",
                "Hope you're well" + (firstName != null ? ", " + namePart : "") + " — small nudge on " + ticketId + ".");
    }

    private static String humanTimePhrase(Ticket ticket, int hours) {
        if (ticket.getTimeInStatusLabel() != null && !ticket.getTimeInStatusLabel().isBlank()) {
            return "for about " + ticket.getTimeInStatusLabel().trim();
        }
        if (hours >= 48) {
            int days = Math.max(1, hours / 24);
            return "for roughly " + days + (days == 1 ? " day" : " days") + " now";
        }
        if (hours > 0) {
            return "for around " + hours + " hours";
        }
        return "recently";
    }

    private static boolean isInProgressState(String friendlyStatus) {
        if (friendlyStatus == null) {
            return false;
        }
        String f = friendlyStatus.toLowerCase(Locale.ROOT);
        return f.contains("progress");
    }

    private static boolean isHighPriority(String priority) {
        if (priority == null) {
            return false;
        }
        String p = priority.toLowerCase(Locale.ROOT);
        return "high".equals(p) || "critical".equals(p);
    }

    private static boolean hasExternalDependency(String dependency) {
        if (dependency == null || dependency.isBlank()) {
            return false;
        }
        return !"NONE".equalsIgnoreCase(dependency.trim());
    }

    private static String dependencyNoun(String dependency) {
        if (dependency == null || dependency.isBlank()) {
            return "another team or system";
        }
        return switch (dependency.trim().toUpperCase(Locale.ROOT)) {
            case "API" -> "a platform or API dependency";
            case "DESIGN" -> "design or UX dependency";
            case "EXTERNAL_TEAM" -> "another team";
            default -> "an external dependency";
        };
    }

    private static List<String> correlationList(Ticket ticket) {
        if (ticket.getCorrelationInsights() == null || ticket.getCorrelationInsights().isEmpty()) {
            return List.of();
        }
        return ticket.getCorrelationInsights();
    }

    private static boolean correlationMentionsDevNotStarted(Ticket ticket) {
        return correlationList(ticket).stream()
                .anyMatch(s -> s != null && s.toLowerCase(Locale.ROOT).contains("development may not"));
    }

    private static boolean correlationMentionsPrReview(Ticket ticket) {
        return correlationList(ticket).stream()
                .anyMatch(
                        s -> {
                            if (s == null) {
                                return false;
                            }
                            String lower = s.toLowerCase(Locale.ROOT);
                            return lower.contains("review is slowing")
                                    || lower.contains("review bottleneck")
                                    || lower.contains("critical review");
                        });
    }

    private static boolean correlationMentionsExternalDepBlocking(Ticket ticket) {
        return correlationList(ticket).stream()
                .anyMatch(
                        s ->
                                s != null
                                        && s.toLowerCase(Locale.ROOT).contains("external dependency blocking"));
    }

    private static String buildObservationParagraph(
            ThreadLocalRandom r,
            Ticket ticket,
            String friendlyStatus,
            int hours,
            int pr,
            List<String> flags,
            String priority) {
        String timePhrase = humanTimePhrase(ticket, hours);
        boolean blocked =
                "Blocked".equalsIgnoreCase(friendlyStatus) || flags.contains(MetricsService.FLAG_BLOCKED);
        boolean inProg = isInProgressState(friendlyStatus);

        StringBuilder o = new StringBuilder(320);
        if (blocked) {
            o.append(
                    pickN(
                            r,
                            "Looks like this is sitting in \"" + friendlyStatus + "\" " + timePhrase + ".",
                            "This one's been marked \"" + friendlyStatus + "\" " + timePhrase + ".",
                            "Quick read — \"" + friendlyStatus + "\" has been the status " + timePhrase + "."));
        } else if (inProg) {
            o.append(
                    pickN(
                            r,
                            "This has been in \"" + friendlyStatus + "\" " + timePhrase + ".",
                            "Looks like \"" + friendlyStatus + "\" is where this has lived " + timePhrase + ".",
                            "Quick check — \"" + friendlyStatus + "\" has been the home for this " + timePhrase + "."));
        } else {
            o.append(
                    pickN(
                            r,
                            "This has been sitting in \"" + friendlyStatus + "\" " + timePhrase + ".",
                            "Looks like it's been in \"" + friendlyStatus + "\" " + timePhrase + ".",
                            "From the board, \"" + friendlyStatus + "\" is where things have rested " + timePhrase + "."));
        }

        if (isHighPriority(priority) && hours > HIGH_PRIORITY_DWELL_HOURS) {
            o.append(" Since it’s a high-priority item and has been in this state for a while, I want to make sure it’s actually moving forward — not waiting quietly.");
        } else if (isHighPriority(priority) && hours > 24) {
            o.append(" Given the priority, I’d like to be sure it’s not quietly waiting on something.");
        } else if (isHighPriority(priority)) {
            o.append(" With the priority on this one, I want to make sure nothing is hiding in the cracks.");
        }

        if (hasExternalDependency(ticket.getDependency()) && !"Completed".equalsIgnoreCase(friendlyStatus)) {
            o.append(" Looks like ")
                    .append(dependencyNoun(ticket.getDependency()))
                    .append(" is in the picture — worth keeping that linkage visible so delay doesn’t get misread as “just dev speed.”");
        }

        int bounceSignal =
                Math.max(
                        ticket.getBounceCount() > 0 ? ticket.getBounceCount() : 0,
                        ticket.getPingPongTransitions());
        if ((flags.contains(MetricsService.FLAG_BOUNCING) || bounceSignal >= 2)
                && !"Completed".equalsIgnoreCase(friendlyStatus)) {
            o.append(" ")
                    .append(
                            pickN(
                                    r,
                                    "There’s also meaningful status churn — read that together with dwell, not in isolation.",
                                    "Churn between stages is part of the picture; next to dwell, that usually points to handoff or definition pressure.",
                                    "Movement between columns isn’t noise here — combined with time-in-state, it often explains why the card feels “busy” but slow.",
                                    "Bounces on the board are visible; paired with how long it’s sat, that’s a signal on clarity or ownership, not just throughput."));
        }

        for (String line : correlationList(ticket)) {
            if (line != null && !line.isBlank()) {
                o.append(" ").append(line.trim());
            }
        }

        if (flags.contains(MetricsService.FLAG_PR_DELAY) && !correlationMentionsPrReview(ticket)) {
            if (pr > 0) {
                o.append(" Looks like review or merge is the long pole (about ")
                        .append(pr)
                        .append("h there) — this usually happens when the code is ready but reviewers are stretched.");
            } else {
                o.append(" Work seems ready, but PR review is taking longer than expected — this might be because the queue ahead of this change is heavy.");
            }
        }
        return o.toString().trim();
    }

    /** Why the observation matters — dependency, PR, dwell, churn (manager voice). */
    private static String buildReasoningParagraph(
            ThreadLocalRandom r,
            Ticket ticket,
            String friendlyStatus,
            int hours,
            int pr,
            List<String> flags,
            String priority) {
        boolean blocked =
                "Blocked".equalsIgnoreCase(friendlyStatus) || flags.contains(MetricsService.FLAG_BLOCKED);
        if (blocked) {
            return pickN(
                    r,
                    "Looks like this is currently blocked — we're either waiting on a dependency or approval, or the blocker isn't written down where everyone can see it.",
                    "Usually this means something outside the “next dev step” is in the way — a dependency, an external team, or a missing decision — and it's worth making that explicit.",
                    "In most cases, the next move is clear once we name the actual blocker. This might be because the dependency wasn't called out, or ownership for the next nudge is fuzzy.",
                    "Quick read: blocked often means the real wait isn't coding — it's a decision, an external team, or a missing handoff, and the board won't show that unless we say it out loud.");
        }

        if (flags.contains(MetricsService.FLAG_DEPENDENCY_RISK) && hasExternalDependency(ticket.getDependency())) {
            return pickN(
                    r,
                    "This looks dependent on another team or system, which could be the reason for delay — the Jira clock and the dependency clock are often not the same.",
                    "Looks like "
                            + dependencyNoun(ticket.getDependency())
                            + " is the likely long pole here; this usually happens when sign-off or integration sits outside the assignee’s direct control.",
                    "This might be because work is structurally waiting on "
                            + dependencyNoun(ticket.getDependency())
                            + " — worth naming who owns the next external nudge so we’re not guessing.",
                    "Quick framing: when "
                            + dependencyNoun(ticket.getDependency())
                            + " is in play, the ticket can look “stuck” even when the assignee is doing the right things — worth spelling that out.");
        }

        if (correlationMentionsDevNotStarted(ticket)
                || (flags.contains(MetricsService.FLAG_PR_DATA_MISSING) && pr == 0 && isInProgressState(friendlyStatus))) {
            return pickN(
                    r,
                    "There’s no PR activity yet, which suggests development might not have started — or the tooling link is missing, but the pattern is worth validating.",
                    "Looks like we’re not seeing pull-request signal while this is still marked in progress; this usually happens when scope is still settling or the branch hasn’t been opened yet.",
                    "This might be because coding hasn’t really kicked off yet, or PR data isn’t flowing into our view — either way, a quick human check helps.",
                    "This usually happens when the next slice of work is still being shaped — a short note on intent and owner keeps everyone from guessing.");
        }

        if (flags.contains(MetricsService.FLAG_PR_DELAY) || correlationMentionsPrReview(ticket)) {
            return pickN(
                    r,
                    "Work seems ready, but PR review is taking longer than expected — this usually happens when reviewers are swamped or the change needs another tight loop.",
                    "Looks like the code side is ahead of the review side; this might be because review capacity is the bottleneck, not implementation.",
                    "This often happens when the PR is technically fine but waiting on comments, approvals, or a merge window — small waits there stack up downstream.",
                    "If this feels slow, it's often because review is shared across several threads — the queue doesn't always show up as loudly as Jira dwell.");
        }

        if (flags.contains(MetricsService.FLAG_STUCK) && isInProgressState(friendlyStatus) && hours >= LONG_IN_PROGRESS_HOURS) {
            return pickN(
                    r,
                    "Usually when something stays in progress this long, it means we're either blocked on something, waiting on a handoff, or the next step isn't crisp enough for everyone to act on.",
                    "Often when items linger here, it means we're either blocked on something, waiting on a handoff, or the next step isn't crisp enough for everyone to act on.",
                    "Looks like this has been in flight a while — this might be because the next concrete action isn't obvious to the whole team, not because nobody cares.");
        }

        if (hasExternalDependency(ticket.getDependency()) && !"Completed".equalsIgnoreCase(friendlyStatus)) {
            return pickN(
                    r,
                    "This might be because "
                            + dependencyNoun(ticket.getDependency())
                            + " is involved — even a healthy ticket can look “slow” when the real wait is outside the assignee’s lane.",
                    "Looks like there’s an external dependency tagged — worth double-checking that the board reflects who’s actually on the hook for the next move.",
                    "This usually happens when part of the path depends on another system or team; naming that explicitly keeps the delay from being misread as dev stall.",
                    "Quick check: with "
                            + dependencyNoun(ticket.getDependency())
                            + " in the story, the next step might sit outside the assignee's lane — worth spelling that out so expectations stay fair.");
        }

        if (flags.contains(MetricsService.FLAG_BOUNCING)) {
            return pickN(
                    r,
                    "The state changes back and forth a bit, which usually points to scope or handoff ambiguity.",
                    "Looks like there's some bouncing between stages — this often happens when the next owner or “done” for the stage isn't crisp.",
                    "This might be because the ticket has been volleying a little; that usually means requirements or ownership weren't quite settled.");
        }

        if (ticket.getBottleneckCategory() != null && !ticket.getBottleneckCategory().isBlank()) {
            return "From the delivery angle, this is showing up as “"
                    + ticket.getBottleneckCategory().trim()
                    + ".” "
                    + (r.nextBoolean()
                            ? "This could be because the next owner isn’t obvious, or the work is waiting on a decision."
                            : "That might mean the next handoff or decision point needs to be named explicitly.");
        }

        return pickN(
                r,
                "Usually this means the next step might be unclear, someone’s waiting on a handoff, or a small dependency is unowned — worth double-checking.",
                "In most cases, this kind of dwell happens when the next concrete action or owner isn’t obvious to the whole team.",
                "This often happens when the definition of “done for this stage” is fuzzy, so the ticket keeps quietly waiting for a nudge.",
                "Looks like a classic “quiet wait” pattern — worth a quick check on who believes they're next and what would count as forward motion.");
    }

    private static String buildActionParagraph(
            ThreadLocalRandom r,
            Ticket ticket,
            String friendlyStatus,
            int hours,
            int pr,
            List<String> flags,
            String priority,
            String bottleneck) {
        boolean blocked =
                "Blocked".equalsIgnoreCase(friendlyStatus) || flags.contains(MetricsService.FLAG_BLOCKED);
        if (blocked) {
            return pickN(
                    r,
                    "Can you take a look today and share what's actually blocking this? If it's a dependency, let's get that clarified so we're not guessing.",
                    "It might help to call out the blocker in the ticket (or the thread) so the right person can step in. If it's a dependency or scope issue, we should get that sorted quickly.",
                    "Let's make sure the next concrete action is visible — can you confirm who's waiting on whom, and what “unblocked” looks like?",
                    "Can you take a look and drop one sentence on the true blocker? That usually unblocks the next conversation faster than another status bump.");
        }
        if (flags.contains(MetricsService.FLAG_DEPENDENCY_RISK) && hasExternalDependency(ticket.getDependency())) {
            return pickN(
                    r,
                    "Can you post who owns the next step on the "
                            + ticket.getDependency()
                            + " dependency and when we expect movement? That keeps Jira, PR, and the external wait tied together.",
                    "Let’s get a dated ask to the owning team for the dependency sign-off — and link the PR thread so review doesn’t look like the only blocker when it isn’t.",
                    "It might help to split “coding done” vs “dependency cleared” in the ticket so stakeholders see where the clock is really running.",
                    "Can you take a look and tag the dependency owner with a concrete ask and date? That usually unblocks the narrative faster than another ping on the assignee alone.");
        }
        if (correlationMentionsDevNotStarted(ticket)
                || (flags.contains(MetricsService.FLAG_PR_DATA_MISSING) && pr == 0 && isInProgressState(friendlyStatus))) {
            return pickN(
                    r,
                    "Can you confirm whether dev work has started, and if not, what the trigger is? If the scope isn’t clear, we can align on the smallest next step.",
                    "If this hasn’t started in the repo yet, a quick note on the intended start and owner would help. Let’s make sure the next move is obvious.",
                    "A short update on where coding stands — or what’s still missing — would help the team line up. If the scope isn’t clear, let’s align on the next concrete step.",
                    "Can you take a look and reply with one line: started vs not started vs waiting on what? That clears the fog for everyone watching the board.");
        }
        if (flags.contains(MetricsService.FLAG_PR_DELAY) || correlationMentionsPrReview(ticket)) {
            return pickN(
                    r,
                    "Can you sync with the reviewer and agree on when this can land, or what would unblock review? That keeps the rest of the plan from drifting.",
                    "If the review is waiting on a small follow-up, it might help to batch that and re-request review with a one-line note. Let’s not let it sit in the queue silently.",
                    "It might help to surface any open questions for the reviewer so we’re not going in circles. A quick nudge in the right channel is usually enough.",
                    "Can you take a look at the open review comments and either resolve or reply in-thread? Small loops there usually unblock merge faster than status changes.");
        }
        if (isInProgressState(friendlyStatus) && hours >= LONG_IN_PROGRESS_HOURS) {
            return pickN(
                    r,
                    "Can you take a look today and confirm what’s holding this up? If it’s a dependency or scope issue, we should get that clarified so it doesn’t delay things further.",
                    "Let’s make sure the next step is explicit — a sentence on what you’re waiting on and who can unblock it helps everyone. If the scope isn’t clear, we can align on the next concrete step.",
                    "A quick read on whether this is blocked vs. just not staffed would help. If the next owner isn’t obvious, we should name it so it doesn’t stall.",
                    "Can you take a look and post the single next action you're waiting on? That usually pulls the right person in without another meeting.");
        }
        if (bottleneck != null && !bottleneck.isBlank() && r.nextInt(3) != 0) {
            return "Can you check in on this with "
                    + (ticket.getId() != null ? ticket.getId() : "the ticket")
                    + " in mind — the bottleneck called out is “"
                    + bottleneck.trim()
                    + ".” A short plan for the next touchpoint would go a long way.";
        }
        return pickN(
                r,
                "Can you take a look today and confirm the next move and any dependencies? A one-line update in the ticket is plenty.",
                "Let’s make sure we’re not quietly waiting on something unowned — a quick status note would help the team calibrate.",
                "It might help to call out the one thing that would make this “done” for the current stage, so the next handoff is smooth.",
                "Can you take a look and thread a short note on who’s driving the next touch? That keeps everyone aligned without another meeting.");
    }

    private static String pickN(ThreadLocalRandom r, String... options) {
        if (options.length == 0) {
            return "";
        }
        return options[r.nextInt(options.length)];
    }

    /**
     * Closing line on delivery impact — shown when the ticket is materially “warm” so the thread
     * lands with appropriate weight.
     */
    private static String buildClosingImpactLine(
            ThreadLocalRandom r,
            Ticket ticket,
            String priority,
            int hours,
            String friendlyStatus,
            List<String> flags) {
        if (!warrantsClosingImpact(ticket, friendlyStatus, flags)) {
            return null;
        }
        List<String> pool = new ArrayList<>();
        pool.add("If this continues, it could impact upcoming delivery timelines.");
        pool.add(
                "If we let this drift, downstream dates get harder to defend — a small nudge now usually helps.");
        pool.add(
                "I'd rather we surface this early than explain a slip later; timelines stay firmer when this stays visible.");
        pool.add(
                "If this pattern holds, release and planning conversations get noisier than they need to be — worth getting ahead of it.");
        if (flags.contains(MetricsService.FLAG_DEPENDENCY_RISK) && hasExternalDependency(ticket.getDependency())) {
            pool.add(
                    "When dependency time and Jira time diverge, dates can slip quietly — worth making that visible so no one is surprised.");
        }
        if (flags.contains(MetricsService.FLAG_PR_DELAY) || correlationMentionsPrReview(ticket)) {
            pool.add(
                    "Looks like review is the gating piece; until that clears, “done” work still isn’t shippable for anyone counting on it.");
        }
        if (isHighPriority(priority)) {
            pool.add(
                    "This is likely to delay downstream QA and may push release timelines if we don’t pull it in today.");
            pool.add(
                    "With this priority, slip here tends to compress QA and harden dates — a same-day touch usually pays off.");
        }
        pool.add(
                "Downstream QA and release planning inherit uncertainty when this shape of risk stays opaque — worth surfacing now.");
        return pool.get(r.nextInt(pool.size()));
    }

    private static boolean warrantsClosingImpact(
            Ticket ticket, String friendlyStatus, List<String> flags) {
        String sev = ticket.getSeverity();
        if ("HIGH".equalsIgnoreCase(sev) || "MEDIUM".equalsIgnoreCase(sev)) {
            return true;
        }
        if ("Blocked".equalsIgnoreCase(friendlyStatus) || flags.contains(MetricsService.FLAG_BLOCKED)) {
            return true;
        }
        return flags.contains(MetricsService.FLAG_STUCK)
                || flags.contains(MetricsService.FLAG_CRITICAL_STUCK)
                || flags.contains(MetricsService.FLAG_PR_DELAY)
                || flags.contains(MetricsService.FLAG_DEPENDENCY_RISK)
                || flags.contains(MetricsService.FLAG_BOUNCING);
    }

    public AiInsightOutcome generateStructuredInsight(Ticket ticket) {
        PipelineMdc.stageAndAction(PipelineMdc.STAGE_AI, PipelineMdc.ACTION_INSIGHT_REQUEST);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String friendlyStatus =
                ticket.getDisplayStatus() != null
                        ? ticket.getDisplayStatus()
                        : TicketDisplayMapper.toFriendlyStatus(ticket.getStatus());
        String who = formatOwner(ticket.getAssignee());
        String title = ticket.getSummary() != null ? ticket.getSummary().trim() : "this work item";
        if (title.length() > 120) {
            title = title.substring(0, 117) + "...";
        }

        int dwell = ticket.getTimeInState();
        int pr = ticket.getPrTime();
        List<String> flags = ticket.getFlags() != null ? ticket.getFlags() : List.of();
        String bottleneck = ticket.getBottleneckCategory() != null ? ticket.getBottleneckCategory() : "Delivery timing";
        String priority = ticket.getPriority() != null ? ticket.getPriority() : "Medium";

        String category = resolvePrimaryCategory(ticket, friendlyStatus, dwell, pr, flags);
        String reasoning =
                buildStructuredReasoning(r, ticket, friendlyStatus, dwell, pr, flags, priority);
        String rootCause =
                category
                        + " — "
                        + buildCategoryNarrative(
                                r, ticket, category, friendlyStatus, dwell, pr, flags, bottleneck, title, priority);
        String impact = buildStructuredImpact(r, ticket, dwell, pr, priority, friendlyStatus, flags);
        String recommendedAction =
                buildStructuredRecommendedAction(r, ticket, who, friendlyStatus, dwell, pr, flags, category);
        String nudge = buildNudge(ticket.getId(), who, friendlyStatus, recommendedAction);
        String confidence = pickConfidence(ticket, ticket.getSeverity(), flags, category, dwell);

        TicketInsightPayload payload =
                TicketInsightPayload.builder()
                        .reasoning(reasoning)
                        .rootCause(rootCause)
                        .impact(impact)
                        .recommendedAction(recommendedAction)
                        .nudge(nudge)
                        .confidence(confidence)
                        .build();

        log.info("Local insight engine produced guidance ticketId={}", ticket.getId());
        return new AiInsightOutcome(payload, false, false, false, true);
    }

    private static String formatOwner(String assignee) {
        if (assignee == null || assignee.isBlank() || "unassigned".equalsIgnoreCase(assignee.trim())) {
            return "the delivery lead (no individual owner yet)";
        }
        return assignee.trim();
    }

    private static boolean isUnassignedTicket(Ticket ticket) {
        String a = ticket.getAssignee();
        return a == null
                || a.isBlank()
                || "unassigned".equalsIgnoreCase(a.trim())
                || "Unassigned".equalsIgnoreCase(a.trim());
    }

    private static long meaningfulFlagCount(List<String> flags) {
        return flags.stream()
                .filter(
                        f ->
                                f != null
                                        && !MetricsService.FLAG_PR_DATA_MISSING.equals(f)
                                        && !MetricsService.FLAG_DATA_INSUFFICIENT.equals(f))
                .count();
    }

    /**
     * Single primary PMO category — ordered so the most actionable constraint wins for labeling.
     */
    private static String resolvePrimaryCategory(
            Ticket ticket, String status, int dwell, int pr, List<String> flags) {
        if (isUnassignedTicket(ticket)) {
            return CAT_OWNERSHIP_GAP;
        }
        boolean blocked =
                "Blocked".equalsIgnoreCase(status) || flags.contains(MetricsService.FLAG_BLOCKED);
        boolean dep = hasExternalDependency(ticket.getDependency());
        if ((blocked && dep)
                || correlationMentionsExternalDepBlocking(ticket)
                || (flags.contains(MetricsService.FLAG_DEPENDENCY_RISK) && dep)) {
            return CAT_DEPENDENCY_BLOCKER;
        }
        if (flags.contains(MetricsService.FLAG_PR_DELAY) || correlationMentionsPrReview(ticket)) {
            return CAT_REVIEW_BOTTLENECK;
        }
        int bounce = Math.max(ticket.getBounceCount(), ticket.getPingPongTransitions());
        if (flags.contains(MetricsService.FLAG_BOUNCING) || bounce >= 2) {
            return CAT_SCOPE_AMBIGUITY;
        }
        if (correlationMentionsDevNotStarted(ticket)
                || (flags.contains(MetricsService.FLAG_PR_DATA_MISSING)
                        && pr == 0
                        && isInProgressState(status))) {
            return CAT_EXECUTION_DELAY;
        }
        if (flags.contains(MetricsService.FLAG_STUCK)
                || flags.contains(MetricsService.FLAG_CRITICAL_STUCK)
                || dwell > STUCK_HOURS) {
            return CAT_EXECUTION_DELAY;
        }
        return CAT_EXECUTION_DELAY;
    }

    private static String buildStructuredReasoning(
            ThreadLocalRandom r,
            Ticket ticket,
            String friendlyStatus,
            int dwell,
            int pr,
            List<String> flags,
            String priority) {
        boolean high = isHighPriority(priority);
        boolean inProg = isInProgressState(friendlyStatus);
        boolean blocked =
                "Blocked".equalsIgnoreCase(friendlyStatus) || flags.contains(MetricsService.FLAG_BLOCKED);
        boolean dep = hasExternalDependency(ticket.getDependency());
        boolean noPr = pr == 0 || !ticket.isPrDataAvailable();
        int bounce = Math.max(ticket.getBounceCount(), ticket.getPingPongTransitions());
        boolean prSlow = flags.contains(MetricsService.FLAG_PR_DELAY) || pr >= PR_SLOW_HOURS;

        if (isUnassignedTicket(ticket)) {
            return pickN(
                    r,
                    "No single DRI shows on the card — dwell and risk flags still accumulate, which usually means routing and accountability are fuzzy, not that nobody cares.",
                    "Ownership is ambiguous while priority is "
                            + priority
                            + " and status is \""
                            + friendlyStatus
                            + "\"; that pairing often explains quiet stalls better than technical difficulty.",
                    "Unassigned work with "
                            + dwell
                            + "h in \""
                            + friendlyStatus
                            + "\" invites duplicate pings and missed nudges — the signal is as much about process as code.",
                    "The board cannot show who is accountable for the next motion — combine that with dwell and PR at "
                            + pr
                            + "h and you get a classic ownership-gap pattern.");
        }

        if (high && inProg && dwell >= FIVE_DAYS_IN_STATE_HOURS && noPr && dep) {
            return pickN(
                    r,
                    "This is a high-priority item that has been in progress for over 5 days, with no meaningful PR movement and an external dependency. This combination typically indicates a coordination gap rather than just execution delay.",
                    "Elevated priority, roughly a week in progress, thin PR motion, and an external dependency in play — that set usually reads as coordination or handoff drag, not slow typing.",
                    "The board shows active progress at high priority, but PR and dependency signals disagree — that mismatch is where PMO attention usually lands first.",
                    "Priority is high while engineering artifacts and the dependency lane stay quiet — stakeholders should assume the clock is running on coordination, not just commits.");
        }
        if (correlationMentionsExternalDepBlocking(ticket) || (blocked && dep)) {
            return pickN(
                    r,
                    "Blocked or long dwell alongside an external dependency means Jira time and dependency time can diverge — the true constraint may be invisible in a quick scan of assignee activity.",
                    "The card signals a wait on another team or system; execution here may be idle even when calendars look full.",
                    "External dependency plus blocked or sticky status usually puts the unblock outside the assignee’s direct control — name it so delay isn’t misread as bandwidth.",
                    "Dependency and workflow state reinforce each other here; the risk is inferring “in progress” as deep engineering when the long pole is elsewhere.");
        }
        if (correlationMentionsPrReview(ticket) && high) {
            return pickN(
                    r,
                    "Review dwell is elevated on something marked urgent — reviewer bandwidth and merge sequencing are likely the constraining resource, not unfamiliarity with the code.",
                    "High priority with a slow PR queue is a critical-path pattern through approvals — small waits at the top echo into QA and release.",
                    "Jira says “now” while review time says “waiting” — that tension is worth resolving before dates compress downstream.",
                    "Urgent classification with review lag usually means we’re serializing on a few approvers or a heavy diff, not missing intent to ship.");
        }
        if (correlationMentionsDevNotStarted(ticket)
                || (flags.contains(MetricsService.FLAG_PR_DATA_MISSING) && noPr && inProg)) {
            return pickN(
                    r,
                    "In progress with little or no PR signal suggests development may not have started — or telemetry is missing — but either way the narrative and the repo are out of sync.",
                    "Status is forward while branch/PR evidence is not — that gap often means scope, sequencing, or a silent gate upstream of commits.",
                    "Cadence in Jira is ahead of engineering artifacts; validate intent, owner, and what “started” means before the next status bump.",
                    "No PR line while the card sits in an active lane deserves a human pass on whether work truly started or tooling is masking motion.");
        }
        if (flags.contains(MetricsService.FLAG_BOUNCING) || bounce >= 3) {
            return pickN(
                    r,
                    "Churn across states with meaningful bounce count points to scope or ownership ambiguity — the ticket moves before “done for this step” stabilizes.",
                    "Ping-pong between columns burns calendar even when effort exists; paired with dwell, it often precedes a re-scoping conversation.",
                    "Bouncing labels correlate with unclear acceptance or handoffs, not malice — tightening that cuts noise for everyone watching release.",
                    "Multiple workflow reversions mean the ticket and reality disagreed more than once — reconcile before the next push.");
        }
        if (prSlow && inProg) {
            return pickN(
                    r,
                    "Long PR cycles while still nominally in flight pull date risk into QA — merge is not a footnote on this item.",
                    "Review time dominates the profile; implementation may be fine while the gating step eats the calendar.",
                    "The shape is “ready but not landed” — downstream validation has not really begun.",
                    "PR dwell stacked on in-progress status usually means parallel work exists but release ordering still flows through one choke point.");
        }
        String timeN = humanTimePhrase(ticket, dwell);
        return pickN(
                r,
                "Composite read: "
                        + priority
                        + " priority, \""
                        + friendlyStatus
                        + "\" "
                        + timeN
                        + ", PR at "
                        + pr
                        + "h, "
                        + (dep ? "external dependency tagged" : "no external dependency")
                        + ", bounce/churn "
                        + bounce
                        + ", and "
                        + meaningfulFlagCount(flags)
                        + " substantive risk flags — use that set to judge whether this is sequencing, scope, or ownership.",
                "Taken together — priority "
                        + priority
                        + ", status "
                        + friendlyStatus
                        + " for "
                        + timeN
                        + ", PR time "
                        + pr
                        + "h, and dependency "
                        + (dep ? ticket.getDependency() : "NONE")
                        + " — ask what the next provable forward motion is, not just the next meeting.",
                "Snapshot: "
                        + priority
                        + " / "
                        + friendlyStatus
                        + " / dwell "
                        + dwell
                        + "h / PR "
                        + pr
                        + "h / bounce "
                        + bounce
                        + " — flags temper the story; read them as evidence, not decoration.",
                "Senior read: align "
                        + friendlyStatus
                        + " with PR "
                        + pr
                        + "h and "
                        + (dep ? dependencyNoun(ticket.getDependency()) : "no external wait")
                        + " — when those disagree for "
                        + timeN
                        + ", assume a coordination thread until proven otherwise.");
    }

    private static String buildCategoryNarrative(
            ThreadLocalRandom r,
            Ticket ticket,
            String category,
            String status,
            int dwell,
            int pr,
            List<String> flags,
            String bottleneck,
            String title,
            String priority) {
        return switch (category) {
            case CAT_DEPENDENCY_BLOCKER -> pickN(
                    r,
                    "\""
                            + title
                            + "\" is gated on "
                            + dependencyNoun(ticket.getDependency())
                            + " while dwell accumulates in \""
                            + status
                            + "\" — assignee activity alone rarely clears that clock.",
                    "Dependency path is on the critical timeline for \""
                            + title
                            + "\"; Jira can still look active while the real wait sits outside this team.",
                    "External wait dominates \""
                            + title
                            + "\" — metrics flagged dependency risk alongside "
                            + dwell
                            + "h in \""
                            + status
                            + "\".",
                    "The labeled blocker is dependency-shaped for \""
                            + title
                            + "\"; pair that with "
                            + priority
                            + " priority so ownership of the external nudge is explicit.");
            case CAT_REVIEW_BOTTLENECK -> pickN(
                    r,
                    "Merge and review are the long pole for \""
                            + title
                            + "\""
                            + (pr > 0 ? " (~" + pr + "h in review/merge)" : "")
                            + " — code may be ready while ship dates still move.",
                    "\""
                            + title
                            + "\" is waiting on review throughput more than implementation; queue health matters as much as the diff.",
                    "PR cycle latency is the headline for \""
                            + title
                            + "\"; treat reviewer capacity and merge windows as first-class risks.",
                    "Review bottleneck on \""
                            + title
                            + "\" — until that clears, downstream QA cannot honestly start the clock.");
            case CAT_SCOPE_AMBIGUITY -> pickN(
                    r,
                    "\""
                            + title
                            + "\" shows churn between states — definitions of done per step or handoff expectations are likely unsettled.",
                    "Bouncing workflow on \""
                            + title
                            + "\" usually means scope or acceptance moved while the ticket was in motion.",
                    "Status volatility on \""
                            + title
                            + "\" points to ambiguity on what “ready” means for the current column.",
                    "\""
                            + title
                            + "\" has ping-ponged; align stakeholders on a single next milestone before more motion.");
            case CAT_OWNERSHIP_GAP -> pickN(
                    r,
                    "\""
                            + title
                            + "\" lacks a single named driver — decisions and nudges will scatter until someone owns closure.",
                    "No crisp owner on \""
                            + title
                            + "\" makes dwell look like a people problem when it is often a routing problem.",
                    "Ownership is ambiguous for \""
                            + title
                            + "\"; assign one accountable engineer or PM for the next external touch.",
                    "\""
                            + title
                            + "\" needs a DRI — without it, blockers linger in the cracks between roles.");
            default -> pickN(
                    r,
                    "\""
                            + title
                            + "\" is in \""
                            + status
                            + "\" at "
                            + priority
                            + " priority with "
                            + dwell
                            + "h dwell — forward motion should be provable in PR or dependency status, not only Jira.",
                    "Execution timing is the story for \""
                            + title
                            + "\" — either work is slower than expected or the next step is not truly unblocked.",
                    "\""
                            + title
                            + "\" has been in \""
                            + status
                            + "\" long enough that we should see concrete engineering progress or an explicit wait reason.",
                    "Classified as execution delay for \""
                            + title
                            + "\" — validate staffing, sequencing, and whether “started” matches reality; delivery note: "
                            + bottleneck
                            + ".");
        };
    }

    private static String buildStructuredImpact(
            ThreadLocalRandom r,
            Ticket ticket,
            int dwell,
            int pr,
            String priority,
            String status,
            List<String> flags) {
        boolean high = isHighPriority(priority);
        boolean dep = hasExternalDependency(ticket.getDependency());
        boolean reviewHeavy = flags.contains(MetricsService.FLAG_PR_DELAY) || pr > PR_SLOW_HOURS;

        if (high && (reviewHeavy || dep || dwell >= FIVE_DAYS_IN_STATE_HOURS)) {
            return pickN(
                    r,
                    "This is likely to delay downstream QA and may push release timelines if not addressed today.",
                    "At this priority and shape, QA and stabilization inherit slip risk — the window to recover without date noise is short.",
                    "Release conversations get harder if this stays opaque; downstream validation and sign-off queues compress when upstream slips.",
                    "Customer-facing dates and internal QA commitments are both sensitive here — same-day clarity usually beats a later hero week.");
        }
        if (reviewHeavy) {
            return pickN(
                    r,
                    "Until review clears, “done” work is not shippable — QA and release ordering stay in a holding pattern.",
                    "Merge delay stacks silently into test and release capacity — teams feel it as thrash even when code is fine.",
                    "Downstream environments and testers wait on landings; calendar burn continues even when implementation is complete.",
                    "Review gating pushes integration and regression work to the right — that is where timelines quietly stretch.");
        }
        if (dep) {
            return pickN(
                    r,
                    "Dependency risk means dates can move without a loud signal in the dev lane — portfolio views understate exposure.",
                    "Other teams’ clocks drive part of this path; planning should treat that wait as explicit, not implied.",
                    "Silent slips happen when Jira shows progress but the external handshake does not — stakeholders deserve that spelled out.",
                    "Integration and QA still pay the price when dependency time runs — even if local coding looks healthy.");
        }
        if (dwell > STUCK_HOURS) {
            return pickN(
                    r,
                    "Long dwell burns context and morale — teams re-litigate the same item instead of finishing adjacent work.",
                    "When items linger, adjacent priorities get starved of attention — portfolio throughput suffers beyond this one card.",
                    "Extended time in \"" + status + "\" makes forecasting noisy for anything sequenced behind this ticket.",
                    "Calendar time lost here is rarely recovered in parallel — downstream dates absorb the shock.");
        }
        return pickN(
                r,
                "Downstream QA and release planning get less predictable when this column stays warm without a crisp story.",
                "Even at medium priority, opaque dwell creates coordination tax for leads and PMs watching the train.",
                "Risk is manageable if surfaced early; if it stays quiet, explainability in release forums suffers.",
                "Stakeholders lose confidence in the plan when the “why” for dwell is unclear — a short narrative fixes a lot.");
    }

    private static String buildStructuredRecommendedAction(
            ThreadLocalRandom r,
            Ticket ticket,
            String who,
            String status,
            int dwell,
            int pr,
            List<String> flags,
            String category) {
        String id = ticket.getId() != null ? ticket.getId() : "this ticket";
        String prefix = who + " should drive today: ";

        if (CAT_DEPENDENCY_BLOCKER.equals(category)) {
            return prefix
                    + pickN(
                            r,
                            "Confirm blocker type (dependency / scope / bandwidth) in one comment; escalate dependency to owning team with a dated ask and link evidence.",
                            "Escalate dependency to owning team — name the DRI, expected resolution date, and what “unblocked” means for QA.",
                            "Post who owns the next step on the "
                                    + (ticket.getDependency() != null ? ticket.getDependency() : "external")
                                    + " path and schedule a 15m sync if the answer is unclear.",
                            "Split “coding done” vs “dependency cleared” on the ticket so review and external waits are not conflated.");
        }
        if (CAT_REVIEW_BOTTLENECK.equals(category)) {
            return prefix
                    + pickN(
                            r,
                            "Treat review as the active bottleneck — list open comments, re-request review with a one-line merge target, and pull a reviewer if capacity is the issue.",
                            "Book a short reviewer sync for "
                                    + id
                                    + " and agree what would unblock merge today vs. next sprint.",
                            "Surface blocking review questions in-thread; if the diff is large, propose a split so review can land incrementally.",
                            "Align merge window with QA — if review slips tonight, call out which test cases move right.");
        }
        if (CAT_SCOPE_AMBIGUITY.equals(category)) {
            return prefix
                    + pickN(
                            r,
                            "Break task into smaller deliverables if scope unclear — write acceptance for the current column only, then move.",
                            "Capture why states bounced in a single comment so the team does not repeat the loop; align PM and eng on “done for this step.”",
                            "Freeze scope for the smallest shippable slice, land it, then open a follow-up for the rest — stop the ping-pong first.",
                            "Run a five-minute scope triage: in vs. out for this sprint — ambiguity is cheaper to fix now than after QA starts.");
        }
        if (CAT_OWNERSHIP_GAP.equals(category)) {
            return prefix
                    + pickN(
                            r,
                            "Assign one owner for "
                                    + id
                                    + " and have them confirm blocker type (dependency / scope / bandwidth) in the ticket.",
                            "Name a DRI and have them own the next external nudge or review ping — unowned work rarely self-resolves.",
                            "Route "
                                    + id
                                    + " to a named engineer with capacity; have them post the single next action they will take today.",
                            "Clarify RACI in one line on the card — who decides, who executes, who approves — then execute.");
        }
        if (flags.contains(MetricsService.FLAG_PR_DELAY) || pr > PR_SLOW_HOURS) {
            return prefix
                    + pickN(
                            r,
                            "Drive review closure on "
                                    + id
                                    + " — confirm blocker type if anything besides review is slowing merge.",
                            "Confirm whether bandwidth or open technical threads gate merge; address the true bottleneck explicitly.",
                            "Pair with a reviewer for "
                                    + id
                                    + " if comments are stale — freshness on feedback often beats more code.",
                            "Escalate merge risk to the EM if the queue is systemic, not just this PR.");
        }
        if (correlationMentionsDevNotStarted(ticket)
                || (pr == 0 && dwell > 24 && isInProgressState(status))) {
            return prefix
                    + pickN(
                            r,
                            "Confirm whether development has started; if scope is unclear, break the task into smaller deliverables with a first milestone date.",
                            "Post started vs. not-started vs. waiting-on-what in one line; if not started, align trigger and owner.",
                            "If tooling might hide PRs, validate in repo; otherwise agree the smallest first commit and who opens the branch.",
                            "Align PM and eng on definition of started — then reflect that honestly in status.");
        }
        return prefix
                + pickN(
                        r,
                        "Confirm blocker type (dependency / scope / bandwidth) and post the single next action with owner and date.",
                        "Step through what blocks \""
                                + status
                                + "\" on "
                                + id
                                + " — short standup note today is enough if it names who waits on whom.",
                        "Check "
                                + id
                                + " in daily sync and write one sentence on what “done” means for this stage.",
                        "If "
                                + (ticket.getBottleneckCategory() != null ? ticket.getBottleneckCategory() : "delivery")
                                + " is the headline, tie the next experiment to that bottleneck explicitly.");
    }

    private static String buildNudge(String id, String who, String status, String action) {
        return "["
                + id
                + "] "
                + "Heads-up: "
                + id
                + " is in \""
                + status
                + "\" — "
                + who
                + " is the best person to drive closure.\n"
                + "Next step: "
                + shortenForNudge(action);
    }

    private static String shortenForNudge(String action) {
        if (action.length() <= 320) {
            return action;
        }
        return action.substring(0, 317) + "...";
    }

    /**
     * Confidence reflects how strongly multiple independent signals agree (severity, flags,
     * correlations, category, dwell) — not randomness.
     */
    private static String pickConfidence(
            Ticket ticket, String severity, List<String> flags, String category, int dwell) {
        int score = 0;
        if ("HIGH".equalsIgnoreCase(severity)) {
            score += 3;
        } else if ("MEDIUM".equalsIgnoreCase(severity)) {
            score += 2;
        } else {
            score += 1;
        }

        score += (int) Math.min(3, meaningfulFlagCount(flags));

        List<String> corr = correlationList(ticket);
        if (corr.size() >= 2) {
            score += 1;
        }

        if (CAT_DEPENDENCY_BLOCKER.equals(category) && flags.contains(MetricsService.FLAG_PR_DELAY)) {
            score += 2;
        }
        if (isHighPriority(ticket.getPriority()) && dwell > HIGH_PRIORITY_DWELL_HOURS) {
            score += 1;
        }
        if (Math.max(ticket.getBounceCount(), ticket.getPingPongTransitions()) >= 3) {
            score += 1;
        }
        if (correlationMentionsExternalDepBlocking(ticket) && correlationMentionsPrReview(ticket)) {
            score += 1;
        }

        if (score >= 8) {
            return "HIGH";
        }
        if (score >= 4) {
            return "MEDIUM";
        }
        return "LOW";
    }
}
