package com.aipmo.agent.service;

import com.aipmo.agent.dto.DataQuality;
import com.aipmo.agent.model.Ticket;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Exactly 30 story-driven synthetic tickets (SIM-1001 … SIM-1030) for controlled demos. Rows are
 * hand-authored; primary data is fully deterministic per row. (A fixed seed can be reintroduced for
 * any future non-scenario fields without changing the 30 story rows.) Secondary timestamps use
 * per-index {@code new Random(42L + index)} for reproducible micro-jitter.
 */
@Service
public class SimulationDataService {

    public static final int TICKET_COUNT = 30;

    /** Placeholder repo used only in synthetic PR URLs (no outbound GitHub calls). */
    private static final String DEMO_GH_PULL_BASE = "https://github.com/demo-org/aipmo-platform/pull/";

    /** Base seed; combined with row index for deterministic, row-local jitter. */
    public static final long RANDOM_SEED_42 = 42L;

    private final List<Ticket> tickets;

    public SimulationDataService() {
        this.tickets = List.copyOf(buildDataset());
    }

    public List<Ticket> loadTickets() {
        return tickets;
    }

    public int ticketCount() {
        return tickets.size();
    }

    private static List<Ticket> buildDataset() {
        if (ROWS.length != TICKET_COUNT) {
            throw new IllegalStateException("ROWS must contain exactly " + TICKET_COUNT + " scenarios");
        }
        List<Ticket> out = new ArrayList<>(TICKET_COUNT);
        for (int i = 0; i < TICKET_COUNT; i++) {
            out.add(buildScenario(ROWS[i]));
        }
        return out;
    }

    private static Ticket buildScenario(SimScenario s) {
        int bounce = s.bounceCount;
        int statusChanges = Math.max(2, 4 + bounce * 2 + s.wfStatus.changesOffset);
        boolean prSignal = s.prTime > 0 || "OPEN".equals(s.prStatus) || "IN_REVIEW".equals(s.prStatus) || "MERGED".equals(s.prStatus);

        Instant now = Instant.now();
        int daysBack = 25 + s.index * 2;
        Random rowRnd = new Random(RANDOM_SEED_42 + s.index);
        Instant created =
                now.minus(daysBack, ChronoUnit.DAYS).plus(rowRnd.nextInt(3), ChronoUnit.HOURS);
        Instant jiraUpd = pickUpdated(s, now, daysBack).plus(rowRnd.nextInt(2), ChronoUnit.HOURS);

        GitSimFields git = buildGitSimFields(s, now, rowRnd, jiraUpd, created);

        return Ticket.builder()
                .id(s.id)
                .summary(s.title)
                .status(s.wfStatus.jira)
                .statusCategory(s.wfStatus.category)
                .createdAt(created)
                .jiraUpdatedAt(jiraUpd)
                .assignee(s.assignee)
                .priority(s.priority)
                .bottleneckCategory(s.bottleneck)
                .timeInState(s.timeInStateHours)
                .prTime(s.prTime)
                .statusChanges(statusChanges)
                .pingPongTransitions(bounce)
                .bounceCount(bounce)
                .prStatus(s.prStatus)
                .dependency(s.dependency)
                .complexity(s.complexity)
                .flags(new ArrayList<>())
                .dataQuality(DataQuality.MOCK)
                .jiraDataAvailable(true)
                .prDataAvailable(prSignal)
                .prNumber(git.prNumber())
                .prUrl(git.prUrl())
                .branchName(git.branchName())
                .lastCommitAt(git.lastCommitAt())
                .prAuthor(git.prAuthor())
                .build();
    }

    /** Synthetic PR metadata: omitted when {@code prStatus} is {@code NOT_CREATED}. */
    private record GitSimFields(
            Integer prNumber,
            String prUrl,
            String branchName,
            Instant lastCommitAt,
            String prAuthor) {}

    private static GitSimFields buildGitSimFields(
            SimScenario s, Instant now, Random rowRnd, Instant jiraUpd, Instant created) {
        if ("NOT_CREATED".equals(s.prStatus)) {
            return new GitSimFields(null, null, null, null, null);
        }
        int prNum = 142 + s.index * 2;
        String prUrl = DEMO_GH_PULL_BASE + prNum;
        String branch =
                "feature/"
                        + s.id.replace("SIM-", "sim-").toLowerCase(Locale.ROOT)
                        + "-"
                        + titleSlug(s.title, 28);
        Instant lastCommit = pickLastCommitAt(s, now, jiraUpd, created, rowRnd);
        String author = gitHubLogin(s.assignee);
        return new GitSimFields(prNum, prUrl, branch, lastCommit, author);
    }

    private static String titleSlug(String title, int maxLen) {
        String lower = title.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        boolean prevHyphen = false;
        for (int i = 0; i < lower.length() && sb.length() < maxLen; i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
                prevHyphen = false;
            } else if ((c == ' ' || c == '-' || c == '—') && sb.length() > 0) {
                if (!prevHyphen) {
                    sb.append('-');
                    prevHyphen = true;
                }
            }
        }
        while (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '-') {
            sb.setLength(sb.length() - 1);
        }
        return sb.length() > 0 ? sb.toString() : "change";
    }

    private static String gitHubLogin(String assignee) {
        if (assignee == null
                || assignee.isBlank()
                || "Unassigned".equalsIgnoreCase(assignee.trim())) {
            return "infra-bot";
        }
        String[] parts = assignee.trim().toLowerCase(Locale.ROOT).split("\\s+");
        return String.join("-", parts);
    }

    private static Instant pickLastCommitAt(
            SimScenario s, Instant now, Instant jiraUpd, Instant created, Random rowRnd) {
        return switch (s.prStatus) {
            case "MERGED" ->
                    jiraUpd.minus(rowRnd.nextInt(10) + 1L, ChronoUnit.HOURS);
            case "IN_REVIEW" ->
                    now.minus(rowRnd.nextInt(28) + 3L, ChronoUnit.HOURS);
            case "OPEN" ->
                    now.minus(rowRnd.nextInt(52) + 8L, ChronoUnit.HOURS);
            default ->
                    clampPast(
                            created.plus(rowRnd.nextInt(96) + 4L, ChronoUnit.HOURS), now);
        };
    }

    private static Instant clampPast(Instant candidate, Instant now) {
        if (candidate.isAfter(now)) {
            return now.minus(1, ChronoUnit.HOURS);
        }
        return candidate;
    }

    /** Deterministic “last update” from scenario index and dwell (no unrepeatable randomness). */
    private static Instant pickUpdated(SimScenario s, Instant now, int daysBack) {
        if ("Review".equals(s.wfStatus.jira)) {
            return now.minus(5 + (s.index % 11), ChronoUnit.HOURS);
        }
        if ("In Progress".equals(s.wfStatus.jira)) {
            return now.minus(4 + (s.index % 9), ChronoUnit.HOURS);
        }
        if ("Done".equals(s.wfStatus.jira)) {
            return now.minus(2 + (s.index % 5), ChronoUnit.DAYS);
        }
        if ("Blocked".equals(s.wfStatus.jira)) {
            return now.minus(8 + (s.index % 6), ChronoUnit.HOURS);
        }
        return now.minus(Math.min(30, 5 + s.timeInStateHours / 48), ChronoUnit.DAYS);
    }

    // --- Static scenario table (single source of truth) ---

    private enum WfStatus {
        BACKLOG("Backlog", "To Do", 0),
        IN_PROGRESS("In Progress", "In Progress", 1),
        BLOCKED("Blocked", "In Progress", 0),
        REVIEW("Review", "In Progress", 0),
        DONE("Done", "Done", 0);

        final String jira;
        final String category;
        final int changesOffset;

        WfStatus(String jira, String category, int changesOffset) {
            this.jira = jira;
            this.category = category;
            this.changesOffset = changesOffset;
        }
    }

    private static final class SimScenario {
        final int index;
        final String id;
        final String title;
        final String assignee;
        final String priority;
        final WfStatus wfStatus;
        final int timeInStateHours;
        final String prStatus;
        final int prTime;
        final String dependency;
        final int bounceCount;
        final String complexity;
        final String bottleneck;

        SimScenario(
                int index,
                String id,
                String title,
                String assignee,
                String priority,
                WfStatus wfStatus,
                int timeInStateHours,
                String prStatus,
                int prTime,
                String dependency,
                int bounceCount,
                String complexity,
                String bottleneck) {
            this.index = index;
            this.id = id;
            this.title = title;
            this.assignee = assignee;
            this.priority = priority;
            this.wfStatus = wfStatus;
            this.timeInStateHours = timeInStateHours;
            this.prStatus = prStatus;
            this.prTime = prTime;
            this.dependency = dependency;
            this.bounceCount = bounceCount;
            this.complexity = complexity;
            this.bottleneck = bottleneck;
        }
    }

    private static final SimScenario[] ROWS = {
            // 5× High + In Progress + no PR (dev not started) — prTime=0, NOT_CREATED
            new SimScenario(0, "SIM-1001", "Payment reconciliation failing for edge cases in nightly batch", "Jordan Ellis", "High", WfStatus.IN_PROGRESS, 88, "NOT_CREATED", 0, "NONE", 0, "MEDIUM", "Work not started or no pull request yet"),
            new SimScenario(1, "SIM-1002", "Loan eligibility calculation mismatch against policy engine v2", "Sam Rivera", "High", WfStatus.IN_PROGRESS, 112, "NOT_CREATED", 0, "NONE", 0, "COMPLEX", "Work not started or no pull request yet"),
            new SimScenario(2, "SIM-1003", "User onboarding API validation issue discovered during bank pilot", "Alex Chen", "High", WfStatus.IN_PROGRESS, 45, "NOT_CREATED", 0, "NONE", 0, "SIMPLE", "Work not started or no pull request yet"),
            new SimScenario(3, "SIM-1004", "Ledger sync timeout under load — triage in progress, no PR opened", "Priya Shah", "High", WfStatus.IN_PROGRESS, 120, "NOT_CREATED", 0, "NONE", 0, "COMPLEX", "Waiting in current status too long"),
            new SimScenario(4, "SIM-1005", "Credit bureau pull retry storm isolated to preprod environments", "Morgan Lee", "High", WfStatus.IN_PROGRESS, 30, "NOT_CREATED", 0, "NONE", 0, "MEDIUM", "Work not started or no pull request yet"),
            // 5× Review + PR cycle > 48h (review bottleneck) — all IN_REVIEW, prTime > 48
            new SimScenario(5, "SIM-1006", "Settlement batch idempotency — security review held on edge cases", "Taylor Brooks", "High", WfStatus.REVIEW, 60, "IN_REVIEW", 60, "NONE", 0, "MEDIUM", "Pull request review slow"),
            new SimScenario(6, "SIM-1007", "Fraud rules engine PR awaiting platform API dependency sign-off", "Riley Patel", "High", WfStatus.REVIEW, 72, "IN_REVIEW", 70, "API", 1, "COMPLEX", "Pull request review slow"),
            new SimScenario(7, "SIM-1008", "Mobile auth token refresh — long-running code review queue", "Casey Nguyen", "High", WfStatus.REVIEW, 90, "IN_REVIEW", 90, "NONE", 0, "MEDIUM", "Pull request review slow"),
            new SimScenario(8, "SIM-1009", "KYC document OCR pipeline — reviewer capacity constraint", "Jamie Ortiz", "Medium", WfStatus.REVIEW, 50, "IN_REVIEW", 55, "NONE", 2, "COMPLEX", "Pull request review slow"),
            new SimScenario(9, "SIM-1010", "Partner webhook contract change — legal and compliance in review", "Avery Kim", "High", WfStatus.REVIEW, 65, "IN_REVIEW", 65, "EXTERNAL_TEAM", 0, "SIMPLE", "Pull request review slow"),
            // 5× Blocked + non-NONE dependency
            new SimScenario(10, "SIM-1011", "Cross-region DR failover blocked on shared infra runbook sign-off", "Quinn Murphy", "High", WfStatus.BLOCKED, 40, "OPEN", 22, "EXTERNAL_TEAM", 0, "COMPLEX", "Waiting on another team or dependency"),
            new SimScenario(11, "SIM-1012", "Rate limit headers for API v3 — blocked on platform cutover", "Reese Johnson", "Medium", WfStatus.BLOCKED, 95, "IN_REVIEW", 35, "API", 0, "MEDIUM", "Waiting on another team or dependency"),
            new SimScenario(12, "SIM-1013", "Cash advance UI — blocked on design system token and motion specs", "Skyler Gupta", "High", WfStatus.BLOCKED, 60, "NOT_CREATED", 0, "DESIGN", 0, "COMPLEX", "Waiting on another team or dependency"),
            new SimScenario(13, "SIM-1014", "Vendor SOC2 attestation gating go-live to production", "Drew Anderson", "Medium", WfStatus.BLOCKED, 55, "OPEN", 12, "EXTERNAL_TEAM", 0, "SIMPLE", "Waiting on another team or dependency"),
            new SimScenario(14, "SIM-1015", "ACH return code mapping — bank API spec freeze for Q2", "Blake Martinez", "High", WfStatus.BLOCKED, 72, "OPEN", 16, "API", 1, "MEDIUM", "Waiting on another team or dependency"),
            // 3× Dev ↔ QA loop (bounceCount ≥ 3) — 15,16,17
            new SimScenario(15, "SIM-1016", "Partial refund amount mismatch — repeated QA bounces to engineering", "Rowan Singh", "Medium", WfStatus.IN_PROGRESS, 100, "OPEN", 28, "NONE", 3, "MEDIUM", "Handoffs and status churn"),
            new SimScenario(16, "SIM-1017", "E2E flakiness on statements view — dev/QA volley on root cause", "Emerson Cole", "High", WfStatus.IN_PROGRESS, 80, "OPEN", 32, "NONE", 4, "COMPLEX", "Handoffs and status churn"),
            new SimScenario(17, "SIM-1018", "Disputes form validation at odds with new design system guidance", "Finley Wright", "Medium", WfStatus.IN_PROGRESS, 55, "OPEN", 24, "DESIGN", 3, "COMPLEX", "Handoffs and status churn"),
            // Backlog + healthy mid-flight work: 10h–150h range — 18–20, 25–27
            new SimScenario(18, "SIM-1019", "PCI scope redaction for new customer reporting exports", "Hayden Park", "Medium", WfStatus.BACKLOG, 36, "NOT_CREATED", 0, "NONE", 0, "MEDIUM", "Competing priorities in backlog"),
            new SimScenario(19, "SIM-1020", "Snowflake cost guardrails and warehouse routing (discovery)", "Logan Bennett", "High", WfStatus.BACKLOG, 20, "NOT_CREATED", 0, "API", 0, "COMPLEX", "Competing priorities in backlog"),
            new SimScenario(20, "SIM-1021", "Dark mode parity for account settings and statements", "Unassigned", "Medium", WfStatus.BACKLOG, 18, "NOT_CREATED", 0, "NONE", 0, "SIMPLE", "No owner assigned"),
            new SimScenario(21, "SIM-1022", "API rate limiter follow-up after Black Friday traffic spike", "Jane Smith", "Low", WfStatus.IN_PROGRESS, 25, "OPEN", 18, "NONE", 0, "SIMPLE", "Team throughput vs commitments"),
            new SimScenario(22, "SIM-1023", "Batch idempotency keys for nightly ledger close job", "Chris Patel", "Medium", WfStatus.IN_PROGRESS, 42, "OPEN", 20, "NONE", 1, "MEDIUM", "Team throughput vs commitments"),
            new SimScenario(23, "SIM-1024", "SLO burn alert misrouted to wrong on-call rotation", "Samira Khan", "High", WfStatus.IN_PROGRESS, 15, "NOT_CREATED", 0, "NONE", 0, "SIMPLE", "Team throughput vs commitments"),
            new SimScenario(24, "SIM-1025", "UI copy: mortgage payoff disclosure typo and formatting", "Noah Williams", "Low", WfStatus.REVIEW, 22, "IN_REVIEW", 20, "NONE", 0, "SIMPLE", "Team throughput vs commitments"),
            new SimScenario(25, "SIM-1026", "Q3 capacity planning and eng hiring guardrails", "Olivia Brown", "Low", WfStatus.BACKLOG, 150, "NOT_CREATED", 0, "NONE", 0, "MEDIUM", "Competing priorities in backlog"),
            new SimScenario(26, "SIM-1027", "A/B test harness for new customer dashboard content cards", "Marcus Johnson", "Medium", WfStatus.BACKLOG, 110, "NOT_CREATED", 0, "DESIGN", 0, "COMPLEX", "Competing priorities in backlog"),
            // 3× Done — healthy baseline, MERGED
            new SimScenario(27, "SIM-1028", "2FA enrollment SMS fallback — rolled out to all retail regions", "Elena Rossi", "Low", WfStatus.DONE, 10, "MERGED", 0, "NONE", 0, "SIMPLE", "None — progressing normally"),
            new SimScenario(28, "SIM-1029", "Audit log export to cold storage with retention policy", "David Park", "Medium", WfStatus.DONE, 8, "MERGED", 0, "NONE", 0, "MEDIUM", "None — progressing normally"),
            new SimScenario(29, "SIM-1030", "Payment instrument tokenization — certificate bundle and HSM attestation", "Sophie Müller", "High", WfStatus.DONE, 12, "MERGED", 0, "API", 0, "COMPLEX", "None — progressing normally"),
    };
}
