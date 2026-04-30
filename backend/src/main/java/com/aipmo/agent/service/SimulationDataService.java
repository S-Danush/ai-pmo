package com.aipmo.agent.service;

import com.aipmo.agent.dto.DataQuality;
import com.aipmo.agent.model.Ticket;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Story-driven synthetic tickets (SIM-1001 … SIM-1052) for controlled demos. Dwell hours stay at
 * or below the metrics “stuck” threshold so portfolio status can sit in AMBER (watch) rather than
 * RED while still showing busy boards. Secondary timestamps use per-index {@code new Random(42L
 * + index)} for reproducible micro-jitter.
 */
@Service
public class SimulationDataService {

    public static final int TICKET_COUNT = 52;

    /** Placeholder repo used only in synthetic PR URLs (no outbound GitHub calls). */
    private static final String DEMO_GH_PULL_BASE = "https://github.com/demo-org/aipmo-platform/pull/";

    /** Base seed; combined with row index for deterministic, row-local jitter. */
    public static final long RANDOM_SEED_42 = 42L;

    private volatile List<Ticket> tickets;
    private final AtomicReference<SimulationScenarioTier> scenarioTier = new AtomicReference<>(SimulationScenarioTier.AMBER);

    public SimulationDataService() {
        this.tickets = List.copyOf(buildDataset(scenarioTier.get()));
    }

    public SimulationScenarioTier getScenarioTier() {
        return scenarioTier.get();
    }

    /** Rebuilds the in-memory dataset for hackathon / demo scenario switching. */
    public synchronized void setScenarioTier(SimulationScenarioTier tier) {
        if (tier == null) {
            tier = SimulationScenarioTier.AMBER;
        }
        scenarioTier.set(tier);
        this.tickets = List.copyOf(buildDataset(tier));
    }

    public List<Ticket> loadTickets() {
        return tickets;
    }

    public int ticketCount() {
        return tickets.size();
    }

    private static List<Ticket> buildDataset(SimulationScenarioTier tier) {
        if (ROWS.length != TICKET_COUNT) {
            throw new IllegalStateException("ROWS must contain exactly " + TICKET_COUNT + " scenarios");
        }
        List<Ticket> out = new ArrayList<>(TICKET_COUNT);
        for (int i = 0; i < TICKET_COUNT; i++) {
            out.add(buildScenario(applyTierToScenario(ROWS[i], tier), tier));
        }
        return out;
    }

    private static SimScenario applyTierToScenario(SimScenario s, SimulationScenarioTier tier) {
        if (tier == SimulationScenarioTier.GREEN) {
            int tis = Math.max(4, (int) Math.round(s.timeInStateHours * 0.38));
            int prt = Math.max(0, (int) Math.round(s.prTime * 0.42));
            int bounce = Math.min(s.bounceCount, 1);
            return new SimScenario(
                    s.index,
                    s.id,
                    s.title,
                    s.assignee,
                    s.priority,
                    s.wfStatus,
                    tis,
                    s.prStatus,
                    prt,
                    s.dependency,
                    bounce,
                    s.complexity,
                    s.bottleneck);
        }
        if (tier == SimulationScenarioTier.RED) {
            int tis = (int) Math.round(s.timeInStateHours * 1.85);
            int prt = (int) Math.round(s.prTime * 1.55);
            int bounce = Math.min(6, s.bounceCount + 1);
            return new SimScenario(
                    s.index,
                    s.id,
                    s.title,
                    s.assignee,
                    s.priority,
                    s.wfStatus,
                    Math.min(220, tis),
                    s.prStatus,
                    Math.min(120, prt),
                    s.dependency,
                    bounce,
                    s.complexity,
                    s.bottleneck);
        }
        return s;
    }

    private static Ticket buildScenario(SimScenario s, SimulationScenarioTier tier) {
        int bounce = s.bounceCount;
        int statusChanges = Math.max(2, 4 + bounce * 2 + s.wfStatus.changesOffset);
        Instant now = Instant.now();
        int daysBack = 25 + s.index * 2;
        Random rowRnd = new Random(RANDOM_SEED_42 + s.index);
        Instant created =
                now.minus(daysBack, ChronoUnit.DAYS).plus(rowRnd.nextInt(3), ChronoUnit.HOURS);
        Instant jiraUpd = pickUpdated(s, now, daysBack).plus(rowRnd.nextInt(2), ChronoUnit.HOURS);

        GitSimFields git = buildGitSimFields(s, now, rowRnd, jiraUpd, created, tier);

        boolean prSignal =
                git.prUrl() != null
                        || git.commitCount() > 0
                        || "MERGED".equals(git.normalizedPrStatus());

        LinkedHashMap<String, Integer> stages = buildStageDurations(s, tier, rowRnd);
        int totalTat = stages.values().stream().mapToInt(Integer::intValue).sum();

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
                .prStatus(git.normalizedPrStatus())
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
                .commitMessages(new ArrayList<>(git.commitMessages()))
                .prTitle(git.prTitle())
                .prLink(git.prLink())
                .commitCount(git.commitCount())
                .deploymentTag(git.deploymentTag())
                .deployed(git.deployed())
                .deployedAt(git.deployedAt())
                .deployEnvironment(git.deployEnvironment())
                .prAgeHours(git.prAgeHours())
                .reviewerDelayHours(git.reviewerDelayHours())
                .stageDurations(stages)
                .totalTat(totalTat)
                .build();
    }

    /** Fixed SDLC labels for TaT charts — order preserved in JSON. */
    public static final List<String> SDLC_STAGES =
            List.of("BACKLOG", "DEV", "REVIEW", "QA", "UAT", "DONE");

    /**
     * Deterministic stage-hour breakdown: varies by row (stuck DEV, slow REVIEW, bouncing QA) and
     * scenario tier. {@code totalTat} is always the sum of all stage values.
     */
    static LinkedHashMap<String, Integer> buildStageDurations(
            SimScenario s, SimulationScenarioTier tier, Random rowRnd) {
        double tierMul =
                switch (tier) {
                    case GREEN -> 0.62;
                    case RED -> 1.42;
                    default -> 1.0;
                };
        int targetTotal =
                (int)
                        Math.round(
                                (88 + s.index * 4 + rowRnd.nextInt(52) + s.timeInStateHours * 0.35)
                                        * tierMul);
        targetTotal = Math.max(72, Math.min(560, targetTotal));

        double backlog = 10 + rowRnd.nextInt(22);
        double dev = 26 + rowRnd.nextInt(40);
        double review = 16 + rowRnd.nextInt(32);
        double qa = 14 + rowRnd.nextInt(26) + s.bounceCount * 9.0;
        double uat = 8 + rowRnd.nextInt(20);
        double done = "Done".equals(s.wfStatus.jira) ? 12 + rowRnd.nextInt(18) : 0;

        if ("Backlog".equals(s.wfStatus.jira)) {
            backlog += 42;
        }
        if ("In Progress".equals(s.wfStatus.jira)) {
            dev += 22;
            if (s.index % 11 == 1 || s.timeInStateHours >= 72) {
                dev += 58;
            }
            if ("NOT_CREATED".equals(s.prStatus) && s.timeInStateHours >= 40) {
                dev += 35;
            }
        }
        if ("Review".equals(s.wfStatus.jira)) {
            review += 52;
        }
        if ("Blocked".equals(s.wfStatus.jira)) {
            qa += 28;
            dev += 18;
        }
        if (s.bounceCount >= 3) {
            qa += 48;
        }
        if ("Done".equals(s.wfStatus.jira)) {
            done += 18 + rowRnd.nextInt(14);
        }

        double sum = backlog + dev + review + qa + uat + done;
        double scale = sum > 0 ? targetTotal / sum : 1.0;

        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        m.put("BACKLOG", Math.max(0, (int) Math.round(backlog * scale)));
        m.put("DEV", Math.max(0, (int) Math.round(dev * scale)));
        m.put("REVIEW", Math.max(0, (int) Math.round(review * scale)));
        m.put("QA", Math.max(0, (int) Math.round(qa * scale)));
        m.put("UAT", Math.max(0, (int) Math.round(uat * scale)));
        m.put("DONE", Math.max(0, (int) Math.round(done * scale)));

        int total = m.values().stream().mapToInt(Integer::intValue).sum();
        int delta = targetTotal - total;
        if (delta != 0) {
            m.merge("DEV", delta, Integer::sum);
            if (m.get("DEV") < 0) {
                m.put("DEV", 0);
            }
        }
        total = m.values().stream().mapToInt(Integer::intValue).sum();
        if (total != targetTotal) {
            m.merge("BACKLOG", targetTotal - total, Integer::sum);
        }
        return m;
    }

    /**
     * Synthetic Git / PR / deploy line — {@link #normalizedPrStatus()} is {@code OPEN}, {@code
     * MERGED}, or {@code NOT_CREATED} (maps legacy {@code IN_REVIEW} → {@code OPEN}).
     */
    private record GitSimFields(
            Integer prNumber,
            String prUrl,
            String prLink,
            String branchName,
            List<String> commitMessages,
            String prTitle,
            String normalizedPrStatus,
            Instant lastCommitAt,
            String prAuthor,
            int commitCount,
            String deploymentTag,
            boolean deployed,
            Instant deployedAt,
            String deployEnvironment,
            double prAgeHours,
            double reviewerDelayHours) {}

    /** IN_REVIEW is treated as an open PR ({@code OPEN}) for SDLC-style dashboards. */
    private static String normalizeSimPrStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return "NOT_CREATED";
        }
        if ("IN_REVIEW".equals(raw)) {
            return "OPEN";
        }
        return raw;
    }

    /** No commits yet — work not materially started in repo (subset of NOT_CREATED rows). */
    private static boolean devNotStartedRow(SimScenario s) {
        if (!"NOT_CREATED".equals(s.prStatus)) {
            return false;
        }
        int i = s.index;
        return (i <= 3)
                || i == 11
                || (i >= 18 && i <= 20)
                || i == 23
                || i == 25
                || i == 26;
    }

    /** Local commits on branch but no PR opened (NOT_CREATED). */
    private static boolean commitsWithoutPrRow(SimScenario s) {
        int i = s.index;
        return "NOT_CREATED".equals(s.prStatus) && (i == 4 || i == 12 || i == 22 || i == 33 || i == 35);
    }

    private static GitSimFields buildGitSimFields(
            SimScenario s, Instant now, Random rowRnd, Instant jiraUpd, Instant created, SimulationScenarioTier tier) {
        String slug = titleSlug(s.title, 40);
        String branch = "feature/" + s.id + "-" + slug;
        String norm = normalizeSimPrStatus(s.prStatus);
        String author = gitHubLogin(s.assignee);

        if (devNotStartedRow(s)) {
            return new GitSimFields(
                    null,
                    null,
                    null,
                    branch,
                    List.of(),
                    null,
                    "NOT_CREATED",
                    null,
                    author,
                    0,
                    null,
                    false,
                    null,
                    null,
                    0,
                    0);
        }

        if (commitsWithoutPrRow(s)) {
            int n = 14 + rowRnd.nextInt(16);
            List<String> msgs = buildCommitMessages(s.id, s.title, n);
            Instant lastCommit =
                    clampPast(created.plus(rowRnd.nextInt(96) + 4L, ChronoUnit.HOURS), now);
            return new GitSimFields(
                    null,
                    null,
                    null,
                    branch,
                    msgs,
                    null,
                    "NOT_CREATED",
                    lastCommit,
                    author,
                    msgs.size(),
                    null,
                    false,
                    null,
                    null,
                    0,
                    0);
        }

        int prNum = 142 + s.index * 2;
        String prUrl = DEMO_GH_PULL_BASE + prNum;
        String prTitle = s.id + " " + shortTitleForPr(s.title);
        List<String> msgs = buildCommitMessages(s.id, s.title, 16 + rowRnd.nextInt(22));
        Instant lastCommit = pickLastCommitAt(s, now, jiraUpd, created, rowRnd);
        double hoursSinceCommit =
                lastCommit != null ? Math.max(0, ChronoUnit.HOURS.between(lastCommit, now)) : 0;
        double prAge =
                "OPEN".equals(norm) ? Math.max(s.prTime, hoursSinceCommit) : 0;
        prAge = scalePrAgeForTier(prAge, tier);
        double reviewerDelay =
                "OPEN".equals(norm) ? Math.min(prAge, Math.max(4.0, prAge * 0.58)) : 0;
        reviewerDelay = scalePrAgeForTier(reviewerDelay, tier);

        boolean merged = "MERGED".equals(norm);
        boolean deployed = merged && s.index != 51;
        Instant deployedAt =
                deployed ? jiraUpd.minus(rowRnd.nextInt(20), ChronoUnit.HOURS) : null;
        String env =
                merged
                        ? (s.index % 5 == 0 ? "QA" : "PROD")
                        : null;
        String tag =
                merged
                        ? ("release-"
                                + s.id.toLowerCase(Locale.ROOT)
                                + "-v1."
                                + (2 + s.index % 4))
                        : null;

        return new GitSimFields(
                prNum,
                prUrl,
                prUrl,
                branch,
                msgs,
                prTitle,
                norm,
                lastCommit,
                author,
                msgs.size(),
                tag,
                deployed,
                deployedAt,
                env,
                prAge,
                reviewerDelay);
    }

    private static double scalePrAgeForTier(double hours, SimulationScenarioTier tier) {
        if (tier == SimulationScenarioTier.GREEN) {
            return hours * 0.45;
        }
        if (tier == SimulationScenarioTier.RED) {
            return Math.min(180, hours * 1.4);
        }
        return hours;
    }

    private static String shortTitleForPr(String title) {
        if (title == null || title.isBlank()) {
            return "implementation";
        }
        String t = title.trim();
        return t.length() > 72 ? t.substring(0, 69) + "…" : t;
    }

    private static List<String> buildCommitMessages(String id, String title, int n) {
        String slug = titleSlug(title, 32).replace('-', ' ');
        String[] stems = {
            id + " implement core flow — " + slug,
            id + " validation and error paths",
            "refactor: simplify " + slug + " module",
            id + " unit + integration tests",
            "chore: lint and naming conventions",
            id + " address review comments (batch)",
            "perf: query and batch tuning for " + slug,
            id + " telemetry, metrics, and dashboards",
            "fix: race condition in async settlement path",
            id + " security review follow-ups",
            "docs: API contract and runbook updates",
            "ci: stabilize flaky integration tests",
            id + " merge latest main into branch",
            "test: e2e coverage for " + slug,
            id + " feature flag wiring and defaults",
            "merge: release candidate fixes",
            id + " hotfix: null guard on edge branch",
            "refactor: extract service boundary for LMS",
            id + " LOS payload mapping corrections",
            "chore: dependency bumps (patch level)",
            id + " logging correlation IDs across flows",
            "test: load-test harness and stubs",
            id + " canary metrics validation",
            id + " webhook retry policy tuning",
            "perf: reduce N+1 in repayment queries",
            id + " audit trail export hardening",
            "fix: timezone handling in schedules",
            id + " align RBAC with new LOS roles"
        };
        int count = Math.max(1, Math.min(n, 48));
        List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(stems[i % stems.length] + " [" + (i + 1) + "]");
        }
        return Collections.unmodifiableList(lines);
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
            // 52 tickets: one heavy lane (~8 active), one light lane (1–2 active), others mid; Done + Blocked + long dwell
            new SimScenario(0, "SIM-1001", "KYC verification failing for PAN validation - LOS onboarding", "Dhanush Balaji", "High", WfStatus.IN_PROGRESS, 14, "NOT_CREATED", 0, "NONE", 0, "MEDIUM", "Implementation not started yet"),
            new SimScenario(1, "SIM-1002", "Loan disbursement delay due to sponsor bank API timeout", "Dhanush Balaji", "High", WfStatus.IN_PROGRESS, 96, "NOT_CREATED", 0, "NONE", 0, "MEDIUM", "Implementation not started yet"),
            new SimScenario(2, "SIM-1003", "EMI schedule mismatch in LMS repayment module", "Dhanush Balaji", "High", WfStatus.IN_PROGRESS, 16, "NOT_CREATED", 0, "NONE", 0, "MEDIUM", "Implementation not started yet"),
            new SimScenario(3, "SIM-1004", "Credit underwriting rule misfiring for salaried applicants", "Dhanush Balaji", "High", WfStatus.BLOCKED, 88, "OPEN", 30, "API", 0, "MEDIUM", "Waiting on external bank API"),
            new SimScenario(4, "SIM-1005", "Ledger reconciliation inconsistency between LOS and LMS", "Dhanush Balaji", "High", WfStatus.IN_PROGRESS, 18, "NOT_CREATED", 0, "NONE", 1, "MEDIUM", "Implementation not started yet"),
            new SimScenario(5, "SIM-1006", "NACH mandate registration failing for joint accounts", "Dhanush Balaji", "Medium", WfStatus.REVIEW, 20, "IN_REVIEW", 41, "NONE", 2, "MEDIUM", "Code review is taking longer than usual"),
            new SimScenario(6, "SIM-1007", "Co-lending partner pricing - bank API confirmation", "Dhanush Balaji", "Medium", WfStatus.REVIEW, 21, "IN_REVIEW", 44, "API", 1, "COMPLEX", "Code review is taking longer than usual"),
            new SimScenario(7, "SIM-1008", "Loan foreclosure settlement - code review queue", "Dhanush Balaji", "Medium", WfStatus.REVIEW, 22, "IN_REVIEW", 47, "NONE", 1, "MEDIUM", "Code review is taking longer than usual"),
            new SimScenario(8, "SIM-1009", "Income document OCR - reviewer capacity", "Batladinne Mythilipriya", "High", WfStatus.BLOCKED, 19, "OPEN", 20, "EXTERNAL_TEAM", 2, "MEDIUM", "Waiting on partner or compliance sign-off"),
            new SimScenario(9, "SIM-1010", "NBFC regulatory reporting - compliance review", "Dhanush Balaji", "Medium", WfStatus.BLOCKED, 20, "OPEN", 21, "API", 0, "MEDIUM", "Waiting on external bank API"),
            new SimScenario(10, "SIM-1011", "LOS-core banking cutover - infra sign-off", "Dhanush Balaji", "High", WfStatus.BLOCKED, 21, "OPEN", 22, "DESIGN", 1, "MEDIUM", "Business rule not finalized"),
            new SimScenario(11, "SIM-1012", "LMS portal refresh - design approval", "Dhanush Balaji", "Medium", WfStatus.BLOCKED, 13, "NOT_CREATED", 0, "DESIGN", 2, "MEDIUM", "Business rule not finalized"),
            new SimScenario(12, "SIM-1013", "Top-up loan eligibility - policy finalization", "Dhanush Balaji", "Medium", WfStatus.BLOCKED, 16, "NOT_CREATED", 0, "NONE", 0, "MEDIUM", "Business rule not finalized"),
            new SimScenario(13, "SIM-1014", "Credit bureau score refresh - vendor freeze", "Dhanush Balaji", "Medium", WfStatus.BLOCKED, 13, "OPEN", 27, "API", 0, "MEDIUM", "Waiting on external bank API"),
            new SimScenario(14, "SIM-1015", "Bulk disbursement file - sponsor bank timeout", "Dhanush Balaji", "Medium", WfStatus.BLOCKED, 15, "OPEN", 28, "API", 1, "MEDIUM", "Waiting on external bank API"),
            new SimScenario(15, "SIM-1016", "EMI bounce handling - QA rework", "Sindhu Manickam", "Medium", WfStatus.IN_PROGRESS, 16, "OPEN", 35, "NONE", 3, "MEDIUM", "Rework between QA and engineering"),
            new SimScenario(16, "SIM-1017", "Loan statement PDF layout - QA feedback", "Sindhu Manickam", "Medium", WfStatus.IN_PROGRESS, 17, "OPEN", 36, "NONE", 4, "COMPLEX", "Rework between QA and engineering"),
            new SimScenario(17, "SIM-1018", "Loan restructuring workflow - collections scope", "Sindhu Manickam", "Medium", WfStatus.IN_PROGRESS, 18, "OPEN", 37, "DESIGN", 3, "COMPLEX", "Business rule not finalized"),
            new SimScenario(18, "SIM-1019", "Collateral release automation - discovery", "Sindhu Manickam", "Medium", WfStatus.BACKLOG, 10, "NOT_CREATED", 0, "NONE", 0, "MEDIUM", "Competing backlog priorities"),
            new SimScenario(19, "SIM-1020", "Interest rate revision propagation - LMS", "Sindhu Manickam", "High", WfStatus.BACKLOG, 11, "NOT_CREATED", 0, "API", 1, "MEDIUM", "Competing backlog priorities"),
            new SimScenario(20, "SIM-1021", "LMS audit trail export - owner TBD", "Unassigned", "Medium", WfStatus.BACKLOG, 12, "NOT_CREATED", 0, "NONE", 2, "MEDIUM", "No owner assigned"),
            new SimScenario(21, "SIM-1022", "Prepayment penalty calculation - implementation", "Dhanush Balaji", "Medium", WfStatus.IN_PROGRESS, 14, "OPEN", 22, "NONE", 0, "MEDIUM", "Team throughput vs commitments"),
            new SimScenario(22, "SIM-1023", "Loan sanction letter merge fields - implementation", "Dhanush Balaji", "Medium", WfStatus.IN_PROGRESS, 17, "NOT_CREATED", 0, "NONE", 1, "MEDIUM", "Team throughput vs commitments"),
            new SimScenario(23, "SIM-1024", "LOS dashboard KPI drill-down - build start", "Mohamed Afridi", "Medium", WfStatus.IN_PROGRESS, 12, "NOT_CREATED", 0, "NONE", 2, "MEDIUM", "Implementation not started yet"),
            new SimScenario(24, "SIM-1025", "Minor sanction letter wording - compliance", "Mohamed Afridi", "Medium", WfStatus.REVIEW, 16, "IN_REVIEW", 22, "NONE", 0, "MEDIUM", "Team throughput vs commitments"),
            new SimScenario(25, "SIM-1026", "Annual LMS maintenance window - comms", "Mohamed Afridi", "Medium", WfStatus.BLOCKED, 52, "OPEN", 28, "API", 1, "MEDIUM", "Waiting on external bank API"),
            new SimScenario(26, "SIM-1027", "A/B test loan funnel - experience design", "Abdul Rasheed", "Medium", WfStatus.BACKLOG, 11, "NOT_CREATED", 0, "DESIGN", 2, "MEDIUM", "Competing backlog priorities"),
            new SimScenario(27, "SIM-1028", "Mobile OTP fallback - login released", "Abdul Rasheed", "Medium", WfStatus.IN_PROGRESS, 17, "OPEN", 20, "NONE", 0, "MEDIUM", "Team throughput vs commitments"),
            new SimScenario(28, "SIM-1029", "Regulatory audit log export - cold storage released", "Batladinne Mythilipriya", "Low", WfStatus.IN_PROGRESS, 11, "OPEN", 21, "NONE", 1, "MEDIUM", "Team throughput vs commitments"),
            new SimScenario(29, "SIM-1030", "Card tokenization scope - collections released", "Batladinne Mythilipriya", "Low", WfStatus.IN_PROGRESS, 12, "OPEN", 22, "NONE", 2, "MEDIUM", "Team throughput vs commitments"),
            new SimScenario(30, "SIM-1031", "UPI mandate retry storm - LOS payments", "Harshini Dhanasekar", "Medium", WfStatus.IN_PROGRESS, 13, "OPEN", 18, "NONE", 0, "MEDIUM", "Team throughput vs commitments"),
            new SimScenario(31, "SIM-1032", "Gold loan LTV recalculation - appraisal API", "Harshini Dhanasekar", "Medium", WfStatus.IN_PROGRESS, 14, "OPEN", 19, "NONE", 1, "MEDIUM", "Team throughput vs commitments"),
            new SimScenario(32, "SIM-1033", "Co-borrower consent SMS - LMS notifications", "Harshini Dhanasekar", "Medium", WfStatus.IN_PROGRESS, 15, "OPEN", 20, "NONE", 2, "MEDIUM", "Team throughput vs commitments"),
            new SimScenario(33, "SIM-1034", "Foreclosure notice template - regional variants", "Madhumathi Muralidharan", "Medium", WfStatus.IN_PROGRESS, 19, "NOT_CREATED", 0, "NONE", 0, "MEDIUM", "Team throughput vs commitments"),
            new SimScenario(34, "SIM-1035", "NPA tagging rules - collections engine", "Madhumathi Muralidharan", "Medium", WfStatus.IN_PROGRESS, 17, "OPEN", 22, "NONE", 1, "MEDIUM", "Team throughput vs commitments"),
            new SimScenario(35, "SIM-1036", "Partner webhook DLQ - replay tooling", "Madhumathi Muralidharan", "Medium", WfStatus.IN_PROGRESS, 20, "NOT_CREATED", 0, "NONE", 2, "MEDIUM", "Team throughput vs commitments"),
            new SimScenario(36, "SIM-1037", "Statement interest accrual - rounding fix", "Mohamed Afridi", "Medium", WfStatus.IN_PROGRESS, 12, "OPEN", 19, "NONE", 0, "MEDIUM", "Team throughput vs commitments"),
            new SimScenario(37, "SIM-1038", "Delinquency bucket migration - batch job", "Mohamed Afridi", "Medium", WfStatus.IN_PROGRESS, 13, "OPEN", 20, "NONE", 1, "MEDIUM", "Team throughput vs commitments"),
            new SimScenario(38, "SIM-1039", "Customer self-serve payoff quote - LMS API", "Mohamed Afridi", "Medium", WfStatus.IN_PROGRESS, 14, "OPEN", 21, "NONE", 2, "MEDIUM", "Team throughput vs commitments"),
            new SimScenario(39, "SIM-1040", "Branch cash deposit limits - policy engine", "Ravindra Dayal", "Low", WfStatus.IN_PROGRESS, 12, "OPEN", 14, "NONE", 0, "MEDIUM", "Team throughput vs commitments"),
            new SimScenario(40, "SIM-1041", "Sponsor bank cert rotation - TLS handshake", "Dhanush Balaji", "Medium", WfStatus.DONE, 14, "MERGED", 0, "NONE", 0, "SIMPLE", "None - progressing normally"),
            new SimScenario(41, "SIM-1042", "LOS field audit - PII masking", "Dhanush Balaji", "Low", WfStatus.DONE, 15, "MERGED", 0, "NONE", 0, "SIMPLE", "None - progressing normally"),
            new SimScenario(42, "SIM-1043", "LMS amortization holiday - COVID carryover", "Sindhu Manickam", "Medium", WfStatus.DONE, 10, "MERGED", 0, "NONE", 0, "SIMPLE", "None - progressing normally"),
            new SimScenario(43, "SIM-1044", "Cross-sell insurance opt-in - journey copy", "Naveenchandhar", "Low", WfStatus.DONE, 11, "MERGED", 0, "NONE", 0, "SIMPLE", "None - progressing normally"),
            new SimScenario(44, "SIM-1045", "Repo rate shock scenario - stress test UI", "Abdul Rasheed", "Medium", WfStatus.DONE, 12, "MERGED", 0, "NONE", 0, "SIMPLE", "None - progressing normally"),
            new SimScenario(45, "SIM-1046", "Collections IVR callback - queue depth", "Batladinne Mythilipriya", "Low", WfStatus.DONE, 13, "MERGED", 0, "NONE", 0, "SIMPLE", "None - progressing normally"),
            new SimScenario(46, "SIM-1047", "Loan closure certificate - PDF merge", "Harshini Dhanasekar", "Medium", WfStatus.DONE, 14, "MERGED", 0, "NONE", 0, "SIMPLE", "None - progressing normally"),
            new SimScenario(47, "SIM-1048", "Merchant EMI subvention - reconciliation", "Madhumathi Muralidharan", "Low", WfStatus.DONE, 15, "MERGED", 0, "NONE", 0, "SIMPLE", "None - progressing normally"),
            new SimScenario(48, "SIM-1049", "Risk scorecard v3 shadow mode - underwriting", "Mohamed Afridi", "Medium", WfStatus.DONE, 10, "MERGED", 0, "NONE", 0, "SIMPLE", "None - progressing normally"),
            new SimScenario(49, "SIM-1050", "Internal tool SSO - SAML bridge", "Ravindra Dayal", "Low", WfStatus.DONE, 11, "MERGED", 0, "NONE", 0, "SIMPLE", "None - progressing normally"),
            new SimScenario(50, "SIM-1051", "Data lake LOS snapshots - incremental load", "Dhanush Balaji", "Medium", WfStatus.DONE, 12, "MERGED", 0, "NONE", 0, "SIMPLE", "None - progressing normally"),
            new SimScenario(51, "SIM-1052", "Mobile app dark mode - LMS statements", "Dhanush Balaji", "Low", WfStatus.DONE, 13, "MERGED", 0, "NONE", 0, "SIMPLE", "None - progressing normally"),

    };
}
