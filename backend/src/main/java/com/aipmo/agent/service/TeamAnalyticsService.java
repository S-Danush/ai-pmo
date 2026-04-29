package com.aipmo.agent.service;

import com.aipmo.agent.dto.AgentRunResponse;
import com.aipmo.agent.dto.CommitsTimeSeriesPointDto;
import com.aipmo.agent.dto.GitActivityPerMemberDto;
import com.aipmo.agent.dto.TeamAnalyticsOverviewDto;
import com.aipmo.agent.dto.TeamAnalyticsResponseDto;
import com.aipmo.agent.dto.TeamMemberAnalyticsDto;
import com.aipmo.agent.dto.TicketDataLoad;
import com.aipmo.agent.dto.WorkloadBarDto;
import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.util.DashboardSort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Aggregates simulated Jira + Git fields into a manager-facing team performance snapshot.
 */
@Service
public class TeamAnalyticsService {

    private static final String PERF_HEALTHY = "HEALTHY";
    private static final String PERF_MODERATE = "MODERATE";
    private static final String PERF_AT_RISK = "AT_RISK";

    private static final String HL_NONE = "NONE";
    private static final String HL_OVER = "OVERLOADED";
    private static final String HL_UNDER = "UNDERUTILIZED";

    private static final Map<String, String> MEMBER_EXPERIENCE =
            Map.ofEntries(
                    Map.entry("Dhanush Balaji", "Associate Technology"),
                    Map.entry("Mohamed Afridi", "Senior Associate"),
                    Map.entry("Naveenchandhar", "Senior Associate"),
                    Map.entry("Sindhu Manickam", "Engineering Manager"),
                    Map.entry("Harshini Dhanasekar", "Senior Associate"),
                    Map.entry("Madhumathi Muralidharan", "Senior Associate"),
                    Map.entry("Abdul Rasheed", "Senior Associate"),
                    Map.entry("Batladinne Mythilipriya", "Intern"),
                    Map.entry("Ravindra Dayal", "Intern"));

    private final AgentResultStore agentResultStore;
    private final TicketDataService ticketDataService;
    private final MetricsService metricsService;
    private final NotifyStateStore notifyStateStore;
    private final GroqInsightService groqInsightService;

    public TeamAnalyticsService(
            AgentResultStore agentResultStore,
            TicketDataService ticketDataService,
            MetricsService metricsService,
            NotifyStateStore notifyStateStore,
            GroqInsightService groqInsightService) {
        this.agentResultStore = agentResultStore;
        this.ticketDataService = ticketDataService;
        this.metricsService = metricsService;
        this.notifyStateStore = notifyStateStore;
        this.groqInsightService = groqInsightService;
    }

    public List<Ticket> loadAnalyzedTicketsMerged() {
        AgentRunResponse last = agentResultStore.getLastRun();
        List<Ticket> base;
        if (last != null) {
            base = last.getTickets();
        } else {
            TicketDataLoad load = ticketDataService.loadTicketData();
            base = metricsService.analyzeTickets(load.tickets());
            DashboardSort.sortForManager(base);
        }
        return notifyStateStore.mergeAll(base);
    }

    public TeamAnalyticsResponseDto buildCurrent() {
        List<Ticket> tickets = loadAnalyzedTicketsMerged();
        Instant now = Instant.now();
        Map<String, List<Ticket>> byAssignee = groupByAssignee(tickets);

        List<String> teamMembers =
                byAssignee.keySet().stream()
                        .filter(TeamAnalyticsService::isRealAssignee)
                        .sorted()
                        .collect(Collectors.toList());

        int completed = (int) tickets.stream().filter(TeamAnalyticsService::isDone).count();
        int underReview = (int) tickets.stream().filter(TeamAnalyticsService::isReview).count();
        int active = (int) tickets.stream().filter(t -> !isDone(t)).count();
        int activeAssigned =
                (int)
                        tickets.stream()
                                .filter(t -> !isDone(t))
                                .filter(t -> isRealAssignee(t.getAssignee()))
                                .count();

        double avgPerDev =
                teamMembers.isEmpty()
                        ? 0.0
                        : Math.round(100.0 * activeAssigned / teamMembers.size()) / 100.0;

        TeamAnalyticsOverviewDto overview =
                TeamAnalyticsOverviewDto.builder()
                        .totalTeamMembers(teamMembers.size())
                        .totalActiveTickets(active)
                        .completedTickets(completed)
                        .ticketsUnderReview(underReview)
                        .avgTicketsPerDeveloper(avgPerDev)
                        .build();

        List<MemberAgg> aggs = new ArrayList<>();
        for (String name : teamMembers) {
            aggs.add(aggregateMember(name, byAssignee.getOrDefault(name, List.of())));
        }

        List<Integer> activeCounts =
                aggs.stream().map(MemberAgg::activeCount).sorted().collect(Collectors.toList());
        int p75Idx = (int) Math.floor(0.75 * Math.max(0, activeCounts.size() - 1));
        int p25Idx = (int) Math.floor(0.25 * Math.max(0, activeCounts.size() - 1));
        int p75 = activeCounts.isEmpty() ? 0 : activeCounts.get(Math.min(p75Idx, activeCounts.size() - 1));
        int p25 = activeCounts.isEmpty() ? 0 : activeCounts.get(Math.max(0, p25Idx));
        double meanActive =
                aggs.stream().mapToInt(MemberAgg::activeCount).average().orElse(0);
        double overloadThreshold = Math.max(p75, meanActive * 1.25);
        double underThreshold = Math.min(p25, Math.max(1, meanActive * 0.55));

        List<WorkloadBarDto> workload = new ArrayList<>();
        for (MemberAgg a : aggs) {
            int ac = a.activeCount();
            String hl;
            if (ac >= overloadThreshold + 0.01) {
                hl = HL_OVER;
            } else if (ac <= underThreshold + 0.01 && ac >= 0) {
                hl = HL_UNDER;
            } else {
                hl = HL_NONE;
            }
            workload.add(
                    WorkloadBarDto.builder()
                            .assigneeName(a.name())
                            .activeTicketCount(ac)
                            .highlight(hl)
                            .build());
        }
        workload.sort(Comparator.comparingInt(WorkloadBarDto::getActiveTicketCount).reversed());

        Map<String, String> highlightByAssignee = new LinkedHashMap<>();
        for (WorkloadBarDto w : workload) {
            highlightByAssignee.put(w.getAssigneeName(), w.getHighlight());
        }

        List<GroqInsightService.TeamMemberBatchRow> batchRows = new ArrayList<>();
        for (MemberAgg a : aggs) {
            String levelForBatch;
            if (a.blocked() >= 2
                    || a.activeCount() >= overloadThreshold + 0.01
                    || (a.assigned() >= 5 && a.completionRate() < 0.15)) {
                levelForBatch = PERF_AT_RISK;
            } else if (a.blocked() == 0
                    && a.activeCount() <= underThreshold + 0.01
                    && a.completionRate() >= 0.2) {
                levelForBatch = PERF_HEALTHY;
            } else {
                levelForBatch = PERF_MODERATE;
            }
            GitActivityPerMemberDto gitForBatch = buildGitActivity(a);
            batchRows.add(
                    new GroqInsightService.TeamMemberBatchRow(
                            a.name(),
                            a.assigned(),
                            a.completed(),
                            a.inProgress(),
                            a.underReview(),
                            a.blocked(),
                            gitForBatch.getTotalCommits(),
                            gitForBatch.getAvgPrReviewTimeHours(),
                            levelForBatch));
        }
        Map<String, String> batchInsights = groqInsightService.tryMemberInsightsBatch(batchRows);

        List<TeamMemberAnalyticsDto> members = new ArrayList<>();
        for (MemberAgg a : aggs) {
            String level;
            if (a.blocked() >= 2 || a.activeCount() >= overloadThreshold + 0.01 || (a.assigned() >= 5 && a.completionRate() < 0.15)) {
                level = PERF_AT_RISK;
            } else if (a.blocked() == 0 && a.activeCount() <= underThreshold + 0.01 && a.completionRate() >= 0.2) {
                level = PERF_HEALTHY;
            } else {
                level = PERF_MODERATE;
            }

            String hl = highlightByAssignee.getOrDefault(a.name(), HL_NONE);
            String capLevel;
            String capLabel;
            if (HL_OVER.equals(hl)) {
                capLevel = "OVERLOADED";
                capLabel = "Overloaded";
            } else if (HL_UNDER.equals(hl)) {
                capLevel = "UNDERUTILIZED";
                capLabel = "Underutilized";
            } else {
                capLevel = "BALANCED";
                capLabel = "Balanced";
            }

            GitActivityPerMemberDto gitMetrics = buildGitActivity(a);
            String nameKey = a.name() != null ? a.name().trim() : "";
            String insight =
                    batchInsights != null && batchInsights.containsKey(nameKey)
                            ? batchInsights.get(nameKey)
                            : groqInsightService.generateTeamMemberPerformanceInsight(
                                    a.name(),
                                    a.assigned(),
                                    a.completed(),
                                    a.inProgress(),
                                    a.underReview(),
                                    a.blocked(),
                                    gitMetrics.getTotalCommits(),
                                    gitMetrics.getAvgPrReviewTimeHours(),
                                    level);

            members.add(
                    TeamMemberAnalyticsDto.builder()
                            .name(a.name())
                            .experience(experienceFor(a.name()))
                            .avatarHue(avatarHue(a.name()))
                            .totalTicketsAssigned(a.assigned())
                            .completedTickets(a.completed())
                            .inProgress(a.inProgress())
                            .underReview(a.underReview())
                            .blocked(a.blocked())
                            .performanceLevel(capLevel)
                            .performanceLabel(capLabel)
                            .aiInsight(insight)
                            .build());
        }

        members.sort(Comparator.comparing(TeamMemberAnalyticsDto::getName));

        List<GitActivityPerMemberDto> gitRows =
                aggs.stream()
                        .map(TeamAnalyticsService::buildGitActivity)
                        .sorted(Comparator.comparing(GitActivityPerMemberDto::getAssigneeName))
                        .collect(Collectors.toList());

        List<CommitsTimeSeriesPointDto> series = buildCommitsOverTime(tickets, now);

        return TeamAnalyticsResponseDto.builder()
                .overview(overview)
                .members(members)
                .gitActivityByMember(gitRows)
                .commitsOverTime(series)
                .workloadByAssignee(workload)
                .generatedAt(now.toString())
                .build();
    }

    private static GitActivityPerMemberDto buildGitActivity(MemberAgg a) {
        int totalCommits = a.totalCommits();
        double perDay = Math.round(100.0 * totalCommits / 21.0) / 100.0;
        double perWeek = Math.round(100.0 * perDay * 7) / 100.0;
        double perMonth = Math.round(100.0 * perDay * 30) / 100.0;
        double avgReview =
                a.reviewSamples() == 0 ? 0.0 : Math.round(100.0 * a.reviewHoursSum() / a.reviewSamples()) / 100.0;
        return GitActivityPerMemberDto.builder()
                .assigneeName(a.name())
                .commitsPerDay(perDay)
                .commitsPerWeek(perWeek)
                .commitsPerMonth(perMonth)
                .prsCreated(a.prsCreated())
                .prsMerged(a.prsMerged())
                .avgPrReviewTimeHours(avgReview)
                .totalCommits(totalCommits)
                .build();
    }

    private static List<CommitsTimeSeriesPointDto> buildCommitsOverTime(List<Ticket> tickets, Instant now) {
        Map<String, Integer> buckets = new LinkedHashMap<>();
        DateTimeFormatter dayFmt = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);
        for (int i = 13; i >= 0; i--) {
            String d = dayFmt.format(now.minusSeconds(i * 86400L));
            buckets.put(d, 0);
        }
        for (Ticket t : tickets) {
            if (t.getLastCommitAt() == null || t.getCommitCount() <= 0) {
                continue;
            }
            String d = dayFmt.format(t.getLastCommitAt());
            if (buckets.containsKey(d)) {
                buckets.merge(d, t.getCommitCount(), Integer::sum);
            }
        }
        List<CommitsTimeSeriesPointDto> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : buckets.entrySet()) {
            out.add(CommitsTimeSeriesPointDto.builder().date(e.getKey()).commits(e.getValue()).build());
        }
        return out;
    }

    private static Map<String, List<Ticket>> groupByAssignee(List<Ticket> tickets) {
        Map<String, List<Ticket>> map = new LinkedHashMap<>();
        for (Ticket t : tickets) {
            String key = normalizeAssignee(t.getAssignee());
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }
        return map;
    }

    private static String normalizeAssignee(String a) {
        if (a == null || a.isBlank()) {
            return "Unassigned";
        }
        return a.trim();
    }

    private static boolean isRealAssignee(String a) {
        if (a == null) {
            return false;
        }
        String u = a.trim();
        return !u.isEmpty() && !"unassigned".equalsIgnoreCase(u);
    }

    private static MemberAgg aggregateMember(String name, List<Ticket> rows) {
        int assigned = rows.size();
        int completed = (int) rows.stream().filter(TeamAnalyticsService::isDone).count();
        int inProgress = (int) rows.stream().filter(TeamAnalyticsService::isInProgress).count();
        int underReview = (int) rows.stream().filter(TeamAnalyticsService::isReview).count();
        int blocked = (int) rows.stream().filter(TeamAnalyticsService::isBlocked).count();
        int totalCommits = rows.stream().mapToInt(Ticket::getCommitCount).sum();
        int prsCreated = (int) rows.stream().filter(TeamAnalyticsService::hasPrRecord).count();
        int prsMerged = (int) rows.stream().filter(t -> "MERGED".equalsIgnoreCase(safe(t.getPrStatus()))).count();
        int activeNonDone = (int) rows.stream().filter(t -> !isDone(t)).count();
        double reviewSum = 0;
        int reviewN = 0;
        for (Ticket t : rows) {
            if (!hasPrRecord(t)) {
                continue;
            }
            Double rd = t.getReviewerDelayHours();
            if (rd != null && rd > 0) {
                reviewSum += rd;
                reviewN++;
            } else if (t.getPrTime() > 0) {
                reviewSum += t.getPrTime();
                reviewN++;
            }
        }
        return new MemberAgg(
                name,
                assigned,
                completed,
                inProgress,
                underReview,
                blocked,
                activeNonDone,
                totalCommits,
                prsCreated,
                prsMerged,
                reviewSum,
                reviewN);
    }

    private static boolean hasPrRecord(Ticket t) {
        String ps = safe(t.getPrStatus());
        if ("MERGED".equalsIgnoreCase(ps) || "OPEN".equalsIgnoreCase(ps)) {
            return true;
        }
        if (t.getPrNumber() != null) {
            return true;
        }
        return t.getPrUrl() != null && !t.getPrUrl().isBlank();
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean isDone(Ticket t) {
        return t.getStatus() != null && "Done".equalsIgnoreCase(t.getStatus().trim());
    }

    private static boolean isReview(Ticket t) {
        return t.getStatus() != null && "Review".equalsIgnoreCase(t.getStatus().trim());
    }

    private static boolean isBlocked(Ticket t) {
        return t.getStatus() != null && "Blocked".equalsIgnoreCase(t.getStatus().trim());
    }

    private static boolean isInProgress(Ticket t) {
        return t.getStatus() != null && "In Progress".equalsIgnoreCase(t.getStatus().trim());
    }

    private static int avatarHue(String name) {
        return Math.floorMod(Objects.hash(name), 360);
    }

    private static String experienceFor(String name) {
        if (name == null || name.isBlank()) {
            return "Engineer";
        }
        return MEMBER_EXPERIENCE.getOrDefault(name.trim(), "Engineer");
    }

    private record MemberAgg(
            String name,
            int assigned,
            int completed,
            int inProgress,
            int underReview,
            int blocked,
            int activeNonDone,
            int totalCommits,
            int prsCreated,
            int prsMerged,
            double reviewHoursSum,
            int reviewSamples) {
        int activeCount() {
            return activeNonDone;
        }

        double completionRate() {
            return assigned <= 0 ? 0.0 : (double) completed / assigned;
        }
    }
}
