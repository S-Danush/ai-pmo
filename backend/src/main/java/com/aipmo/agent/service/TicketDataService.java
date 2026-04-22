package com.aipmo.agent.service;

import com.aipmo.agent.config.IntegrationProperties;
import com.aipmo.agent.config.JiraProperties;
import com.aipmo.agent.dto.DataQuality;
import com.aipmo.agent.dto.GitHubPrLoadResult;
import com.aipmo.agent.dto.TicketDataLoad;
import com.aipmo.agent.dto.TicketDataPath;
import com.aipmo.agent.model.Ticket;
import com.aipmo.agent.util.TicketDisplayMapper;
import com.aipmo.agent.util.TimedCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class TicketDataService {

    private static final Logger log = LoggerFactory.getLogger(TicketDataService.class);

    private final TicketService ticketService;
    private final JiraService jiraService;
    private final GitHubService gitHubService;
    private final IntegrationProperties integrationProperties;
    private final JiraProperties jiraProperties;
    private final TimedCache<List<Ticket>> mergedTicketListCache;

    public TicketDataService(
            TicketService ticketService,
            JiraService jiraService,
            GitHubService gitHubService,
            IntegrationProperties integrationProperties,
            JiraProperties jiraProperties,
            TimedCache<List<Ticket>> mergedTicketListCache) {
        this.ticketService = ticketService;
        this.jiraService = jiraService;
        this.gitHubService = gitHubService;
        this.integrationProperties = integrationProperties;
        this.jiraProperties = jiraProperties;
        this.mergedTicketListCache = mergedTicketListCache;
    }

    public List<Ticket> loadTicketsForAnalysis() {
        return loadTicketData(false).tickets();
    }

    /**
     * @param simulation when true, returns curated demo tickets (no Jira/GitHub, no integration
     *     cache).
     */
    public List<Ticket> loadTicketsForAnalysis(boolean simulation) {
        return loadTicketData(simulation).tickets();
    }

    /**
     * Loads tickets and records how they were sourced (for audit logs).
     */
    public TicketDataLoad loadTicketData(boolean simulation) {
        if (simulation) {
            List<Ticket> tickets = copyTickets(ticketService.getSimulationTickets());
            log.debug("ticketData path={} count={}", TicketDataPath.SIMULATION, tickets.size());
            return new TicketDataLoad(tickets, TicketDataPath.SIMULATION, false, false, DataQuality.MOCK);
        }

        int ttlSec = Math.max(1, integrationProperties.getCacheTtlSeconds());
        Duration ttl = Duration.ofSeconds(ttlSec);

        var cached = mergedTicketListCache.getIfFresh();
        if (cached.isPresent()) {
            List<Ticket> rawCached = cached.get();
            if (rawCached.isEmpty()) {
                mergedTicketListCache.invalidate();
            } else {
                List<Ticket> out = copyTickets(rawCached);
                log.debug("ticketData path={} count={}", TicketDataPath.INTEGRATION_CACHE, out.size());
                Ticket z = out.get(0);
                DataQuality dq = z.getDataQuality() != null ? z.getDataQuality() : DataQuality.MOCK;
                return new TicketDataLoad(
                        out, TicketDataPath.INTEGRATION_CACHE, z.isJiraDataAvailable(), z.isPrDataAvailable(), dq);
            }
        }

        if (!jiraProperties.isComplete()) {
            log.error(
                    "FALLBACK TRIGGERED: Jira configuration incomplete — mock fallback disabled. Missing: {}",
                    jiraProperties.describeConfigurationGaps());
            throw new IllegalStateException(
                    "Jira is not configured. Set the following (for example in backend/config/local-keys.properties): "
                            + jiraProperties.describeConfigurationGaps());
        }

        List<Ticket> jiraTickets = jiraService.fetchTicketsRequired();
        GitHubPrLoadResult prLoad = gitHubService.buildTicketKeyToPrHours();
        boolean jiraDataAvailable = true;
        boolean prDataAvailable = prLoad.prDataAvailable();

        DataQuality quality = prDataAvailable ? DataQuality.HIGH : DataQuality.PARTIAL;
        List<Ticket> merged =
                mergePrTimes(
                        jiraTickets,
                        prLoad.ticketKeyToPrHours(),
                        quality,
                        jiraDataAvailable,
                        prDataAvailable);
        mergedTicketListCache.put(copyTickets(merged), ttl);
        log.debug(
                "ticketData path={} jiraIssues={} prKeyMappings={} jiraDataAvailable={} prDataAvailable={}",
                TicketDataPath.JIRA_GITHUB_MERGED,
                merged.size(),
                prLoad.ticketKeyToPrHours().size(),
                jiraDataAvailable,
                prDataAvailable);
        return new TicketDataLoad(
                copyTickets(merged),
                TicketDataPath.JIRA_GITHUB_MERGED,
                jiraDataAvailable,
                prDataAvailable,
                quality);
    }

    private static List<Ticket> mergePrTimes(
            List<Ticket> fromJira,
            Map<String, Integer> prHours,
            DataQuality dataQuality,
            boolean jiraDataAvailable,
            boolean prDataAvailable) {
        List<Ticket> out = new ArrayList<>(fromJira.size());
        for (Ticket t : fromJira) {
            int pr = prHours.getOrDefault(t.getId(), 0);
            out.add(copyOne(t, pr, dataQuality, jiraDataAvailable, prDataAvailable));
        }
        return out;
    }

    private static Ticket copyOne(
            Ticket t,
            int prOverride,
            DataQuality dataQuality,
            boolean jiraDataAvailable,
            boolean prDataAvailable) {
        return Ticket.builder()
                .id(t.getId())
                .summary(t.getSummary())
                .status(t.getStatus())
                .statusCategory(t.getStatusCategory())
                .createdAt(t.getCreatedAt())
                .jiraUpdatedAt(t.getJiraUpdatedAt())
                .assignee(t.getAssignee())
                .displayStatus(
                        t.getDisplayStatus() != null
                                ? t.getDisplayStatus()
                                : TicketDisplayMapper.toFriendlyStatus(t.getStatus()))
                .progressLabel(t.getProgressLabel())
                .flagSummary(t.getFlagSummary())
                .timeInState(t.getTimeInState())
                .prTime(prOverride)
                .statusChanges(t.getStatusChanges())
                .pingPongTransitions(t.getPingPongTransitions())
                .flags(t.getFlags() != null ? new ArrayList<>(t.getFlags()) : new ArrayList<>())
                .insight(t.getInsight())
                .nudge(t.getNudge())
                .rootCause(t.getRootCause())
                .impact(t.getImpact())
                .recommendedAction(t.getRecommendedAction())
                .severity(t.getSeverity())
                .trendIndicator(t.getTrendIndicator())
                .confidence(t.getConfidence())
                .lastUpdated(t.getLastUpdated())
                .dataQuality(dataQuality)
                .jiraDataAvailable(jiraDataAvailable)
                .prDataAvailable(prDataAvailable)
                .build();
    }

    private static List<Ticket> copyTickets(List<Ticket> source) {
        List<Ticket> copy = new ArrayList<>(source.size());
        for (Ticket t : source) {
            DataQuality dq = t.getDataQuality() != null ? t.getDataQuality() : DataQuality.MOCK;
            copy.add(copyOne(t, t.getPrTime(), dq, t.isJiraDataAvailable(), t.isPrDataAvailable()));
        }
        return copy;
    }
}
