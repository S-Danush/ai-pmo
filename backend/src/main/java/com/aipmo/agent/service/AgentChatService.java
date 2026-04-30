package com.aipmo.agent.service;

import com.aipmo.agent.dto.AgentChatResponseDto;
import com.aipmo.agent.dto.TeamAnalyticsResponseDto;
import com.aipmo.agent.dto.TeamMemberAnalyticsDto;
import com.aipmo.agent.dto.WorkloadBarDto;
import com.aipmo.agent.model.ChatMessage;
import com.aipmo.agent.model.Ticket;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** PMO assistant chat — Groq when configured, otherwise rule-based answers on current portfolio data. */
@Service
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private static final Pattern TICKET_ID_PATTERN = Pattern.compile("\\bSIM-\\d{4}\\b");

    private static final String INTENT_BLOCKED = "BLOCKED";
    private static final String INTENT_HIGH_PRIORITY = "HIGH_PRIORITY";
    private static final String INTENT_TOP_LOAD = "TOP_LOAD";
    private static final String INTENT_OVERLOADED = "OVERLOADED";
    private static final String INTENT_NO_COMMITS = "NO_COMMITS";
    private static final String INTENT_DELAYED_PR = "DELAYED_PR";
    private static final String INTENT_DELAY = "DELAY";
    private static final String INTENT_BOTTLENECK = "BOTTLENECK";
    private static final String INTENT_RISK = "RISK";
    private static final String INTENT_ATTENTION = "ATTENTION";
    private static final String INTENT_GENERIC = "GENERIC";
    private static final String INTENT_DELAY_LIST = "DELAY_LIST";
    private static final String INTENT_COMPLETED = "COMPLETED";
    private static final String INTENT_BLOCKERS = "BLOCKERS";
    private static final String INTENT_NEXT_ACTIONS = "NEXT_ACTIONS";

    /** "What is X working on" — name is non-greedy up to the verb phrase. */
    private static final Pattern WORKING_ON_PATTERN =
            Pattern.compile(
                    "(?i)(?:what\\s+is|what's|what\\s+does)\\s+(.+?)\\s+(?:working\\s+on|doing|handling)(?:\\s+right\\s+now)?\\s*\\??\\s*$");

    private final TeamAnalyticsService teamAnalyticsService;
    private final GroqAIService groqAIService;
    private final ObjectMapper objectMapper;
    private final ChatSessionStore chatSessionStore;

    /** Intent / follow-up scratchpad per logical chat session (separate from persisted messages). */
    private final ConcurrentHashMap<String, IntentScratchpad> intentBySession = new ConcurrentHashMap<>();

    public static final String ASRC_SMALLTALK = "SMALLTALK";
    public static final String ASRC_CAPABILITIES = "CAPABILITIES";
    public static final String ASRC_LOCAL_RULE = "LOCAL_RULE";
    public static final String ASRC_LOCAL_HANDLER = "LOCAL_HANDLER";
    public static final String ASRC_GROQ = "GROQ";
    /** Empty input — client may show example prompts only in this case. */
    public static final String ASRC_PROMPT = "PROMPT";

    public AgentChatService(
            TeamAnalyticsService teamAnalyticsService,
            GroqAIService groqAIService,
            ObjectMapper objectMapper,
            ChatSessionStore chatSessionStore) {
        this.teamAnalyticsService = teamAnalyticsService;
        this.groqAIService = groqAIService;
        this.objectMapper = objectMapper;
        this.chatSessionStore = chatSessionStore;
    }

    private AgentChatResponseDto chatResponse(
            String sessionId,
            String answerSource,
            String detail,
            String message,
            List<String> bullets,
            List<String> referencedTicketIds) {
        if (log.isDebugEnabled()) {
            log.debug("[agent-chat] response source={} sessionId={} detail={}", answerSource, sessionId, detail);
        }
        return AgentChatResponseDto.builder()
                .answerSource(answerSource)
                .message(message)
                .bullets(bullets != null ? bullets : List.of())
                .referencedTicketIds(referencedTicketIds != null ? referencedTicketIds : List.of())
                .sessionId(sessionId)
                .build();
    }

    private enum UnderstoodKind {
        ASSIGNEE,
        WORKING_ON,
        BLOCKED,
        OVERLOADED,
        DELAYED_LIST,
        PROJECT_DELAY,
        RISK,
        BLOCKERS,
        COMPLETED,
        NEXT_ACTIONS,
        GENERIC
    }

    private record Understood(UnderstoodKind kind, String entityFragment, boolean workingOnWording) {}

    private AgentChatResponseDto completeWithGroq(
            String sessionId,
            String q,
            IntentScratchpad session,
            List<Ticket> allTickets,
            TeamAnalyticsResponseDto team,
            Understood understood) {
        List<Ticket> slice = sliceTicketsForModel(allTickets, understood);
        String ticketsJson = buildProjectContextJson(slice, team);
        if (ticketsJson == null || ticketsJson.isBlank() || "{}".equals(ticketsJson)) {
            log.warn("[agent-chat] project JSON context is empty");
        }
        String bottleneckSummary = buildBottleneckSummaryText(allTickets);
        String teamSummary = buildTeamSummaryLine(team);
        String systemPrompt =
                "You are a senior engineering manager for a loan origination and servicing (LOS/LMS) portfolio.\n"
                        + "You MUST answer ONLY from the provided JSON ticket list, team summary, and bottleneck summary.\n"
                        + "Do NOT invent ticket IDs, people, counts, or bottlenecks that are not in the data.\n"
                        + "If the ticket slice is empty, state that plainly — never fabricate examples.\n\n"
                        + "Tone: clear, factual, concise. Use **bold** sparingly for names and counts.\n"
                        + "Answer the user’s exact question. Never reply with a generic \"try asking\" suggestion list.\n"
                        + "Mention SIM-10xx IDs only when they appear in the JSON and directly support your answer.";
        String userBlock = buildDataContextUserMessage(understood.kind(), q, ticketsJson, teamSummary, bottleneckSummary);
        String raw = groqAIService.complete(systemPrompt, userBlock);
        GroqParsed parsed = parseGroqAnswer(raw);
        List<String> refIds = mergeTicketIds(parsed.idsFromText(), allTickets);
        return chatResponse(
                sessionId,
                ASRC_GROQ,
                "referencedTicketIds=" + refIds.size(),
                parsed.message(),
                parsed.bullets(),
                refIds);
    }

    private static String buildDataContextUserMessage(
            UnderstoodKind kind,
            String q,
            String ticketsJson,
            String teamSummary,
            String bottleneckSummary) {
        return "Understood intent tag: "
                + kind
                + "\n\nStructured context (JSON — ticket rows scoped to this question where applicable):\n\n"
                + ticketsJson
                + "\n\n<TEAM SUMMARY>\n"
                + teamSummary
                + "\n</TEAM SUMMARY>\n\n<BOTTLENECK SUMMARY (full active portfolio)>\n"
                + bottleneckSummary
                + "\n</BOTTLENECK SUMMARY>\n\nUser question:\n"
                + q
                + "\n\nGive a grounded answer. When listing tickets, include status and priority from the JSON.";
    }

    private static boolean hasAssistantInHistory(List<ChatMessage> prior) {
        if (prior == null || prior.isEmpty()) {
            return false;
        }
        return prior.stream().anyMatch(m -> "assistant".equalsIgnoreCase(m.getRole()));
    }

    private List<Ticket> refineSliceForChatFollowUp(
            IntentScratchpad session,
            String norm,
            String q,
            List<Ticket> all,
            List<Ticket> primarySlice) {
        if (session.lastReferencedTicketIds.isEmpty()) {
            return primarySlice;
        }
        if (matchesScopedBlockedFollowUp(norm, q)) {
            Set<String> idSet = new HashSet<>(session.lastReferencedTicketIds);
            return all.stream()
                    .filter(
                            t ->
                                    t.getId() != null
                                            && idSet.contains(t.getId())
                                            && t.getStatus() != null
                                            && "Blocked".equalsIgnoreCase(t.getStatus().trim()))
                    .sorted(Comparator.comparing(Ticket::getId))
                    .collect(Collectors.toList());
        }
        return primarySlice;
    }

    private static boolean matchesScopedBlockedFollowUp(String norm, String q) {
        if (!norm.contains("block") && !norm.contains("stuck")) {
            return false;
        }
        return (norm.contains("which") && (norm.contains("one") || norm.contains("ones")))
                || norm.contains("of those")
                || norm.contains("any of them")
                || (q.length() < 60 && norm.contains("which") && norm.contains("ticket"));
    }

    private AgentChatResponseDto completeWithGroqConversationFull(
            String sessionId,
            String q,
            String norm,
            IntentScratchpad session,
            List<Ticket> tickets,
            TeamAnalyticsResponseDto team,
            List<ChatMessage> prior) {
        Understood understood = understandQuery(q, norm, tickets, team);
        List<Ticket> slice = sliceTicketsForModel(tickets, understood);
        slice = refineSliceForChatFollowUp(session, norm, q, tickets, slice);
        String ticketsJson = buildProjectContextJson(slice, team);
        if (ticketsJson == null || ticketsJson.isBlank() || "{}".equals(ticketsJson)) {
            log.warn("[agent-chat] project JSON context is empty (multi-turn)");
        }
        String bottleneckSummary = buildBottleneckSummaryText(tickets);
        String teamSummary = buildTeamSummaryLine(team);
        String systemPrompt =
                "You are a senior engineering manager for a loan origination and servicing (LOS/LMS) portfolio.\n"
                        + "You MUST answer ONLY from the provided JSON ticket list, team summary, and bottleneck summary in the latest user message.\n"
                        + "Use earlier user/assistant turns only to resolve references (people, tickets, pronouns like \"which one\").\n"
                        + "Do NOT invent ticket IDs, people, counts, or bottlenecks that are not in the data.\n"
                        + "If the ticket slice is empty, state that plainly — never fabricate examples.\n\n"
                        + "Tone: clear, factual, concise. Use **bold** sparingly for names and counts.\n"
                        + "Answer the user’s exact question. Never reply with a generic \"try asking\" suggestion list.\n"
                        + "Mention SIM-10xx IDs only when they appear in the JSON and directly support your answer.";
        List<GroqAIService.GroqChatMessage> conv = new ArrayList<>();
        for (ChatMessage m : prior) {
            if (m == null || m.getRole() == null || m.getContent() == null) {
                continue;
            }
            String r = m.getRole().trim();
            if ("user".equalsIgnoreCase(r) || "assistant".equalsIgnoreCase(r)) {
                conv.add(new GroqAIService.GroqChatMessage(r.toLowerCase(Locale.ROOT), m.getContent()));
            }
        }
        String userBlock =
                buildDataContextUserMessage(understood.kind(), q, ticketsJson, teamSummary, bottleneckSummary);
        conv.add(new GroqAIService.GroqChatMessage("user", userBlock));
        String raw = groqAIService.completeConversation(systemPrompt, conv, false);
        GroqParsed parsed = parseGroqAnswer(raw);
        List<String> refIds = mergeTicketIds(parsed.idsFromText(), tickets);
        session.recentUserQueries.add(q);
        trimQueries(session);
        session.lastIntent = intentFromUnderstood(understood);
        if (understood.kind() == UnderstoodKind.ASSIGNEE || understood.kind() == UnderstoodKind.WORKING_ON) {
            session.lastReferencedTicketIds.clear();
            session.lastReferencedTicketIds.addAll(
                    slice.stream()
                            .map(Ticket::getId)
                            .filter(id -> id != null && !id.isBlank())
                            .collect(Collectors.toList()));
            session.lastAssigneeFragment = understood.entityFragment();
        }
        return chatResponse(
                sessionId,
                ASRC_GROQ,
                "referencedTicketIds=" + refIds.size() + " multiTurn=true",
                parsed.message(),
                parsed.bullets(),
                refIds);
    }

    public AgentChatResponseDto processUserQuery(String query, String sessionIdIn) {
        String sessionId =
                sessionIdIn != null && !sessionIdIn.isBlank() ? sessionIdIn.trim() : UUID.randomUUID().toString();
        String q = query == null ? "" : query.trim();
        IntentScratchpad session = intentBySession.computeIfAbsent(sessionId, k -> new IntentScratchpad());
        List<ChatMessage> prior = chatSessionStore.getMessagesOrdered(sessionId);
        return dispatchQuery(sessionId, q, session, prior);
    }

    /**
     * Chat API with server-side history: stores the user message and assistant reply after generating a
     * response.
     */
    public AgentChatResponseDto sendPersistedChatMessage(String sessionId, String message) {
        List<ChatMessage> prior = chatSessionStore.getMessagesOrdered(sessionId);
        IntentScratchpad session = intentBySession.computeIfAbsent(sessionId, k -> new IntentScratchpad());
        AgentChatResponseDto resp = dispatchQuery(sessionId, message, session, prior);
        LocalDateTime now = LocalDateTime.now();
        chatSessionStore.appendMessage(
                ChatMessage.builder()
                        .sessionId(sessionId)
                        .role("user")
                        .content(message)
                        .timestamp(now)
                        .build());
        chatSessionStore.appendMessage(
                ChatMessage.builder()
                        .sessionId(sessionId)
                        .role("assistant")
                        .content(formatAssistantForPersistence(resp))
                        .timestamp(LocalDateTime.now())
                        .build());
        chatSessionStore.maybeSetTitleFromFirstMessage(sessionId, shortenTitleForSession(message));
        chatSessionStore.touchSession(sessionId);
        return resp;
    }

    private AgentChatResponseDto dispatchQuery(
            String sessionId, String q, IntentScratchpad session, List<ChatMessage> prior) {
        List<Ticket> tickets = teamAnalyticsService.loadAnalyzedTicketsMerged();
        TeamAnalyticsResponseDto team = teamAnalyticsService.buildCurrent();

        log.debug("[agent-chat] incoming question sessionId={} chars={}", sessionId, q.length());
        if (log.isDebugEnabled()) {
            log.debug("[agent-chat] incoming text: {}", q);
        }

        if (q.isBlank()) {
            return emptyQueryPrompt(sessionId);
        }

        if (isPureSocialGreeting(q)) {
            session.lastIntent = INTENT_GENERIC;
            session.recentUserQueries.add(q);
            trimQueries(session);
            String reply = smalltalkReply(q.toLowerCase(Locale.ROOT));
            return chatResponse(sessionId, ASRC_SMALLTALK, null, reply, List.of(), List.of());
        }

        String norm = normalizeQueryText(q);
        if (isMetaCapabilitiesQuestion(norm)) {
            session.lastIntent = INTENT_GENERIC;
            session.recentUserQueries.add(q);
            trimQueries(session);
            return chatResponse(sessionId, ASRC_CAPABILITIES, null, capabilitiesReply(), List.of(), List.of());
        }

        if (hasAssistantInHistory(prior) && groqAIService.isEnabled()) {
            log.debug("[agent-chat] Groq conversation (multi-turn) path");
            try {
                return completeWithGroqConversationFull(sessionId, q, norm, session, tickets, team, prior);
            } catch (Exception e) {
                log.error("[agent-chat] Groq conversation failed; falling back: {}", e.toString(), e);
            }
        }

        if (isFollowUpHighPriority(q) && INTENT_BLOCKED.equals(session.lastIntent)) {
            AnswerPayload payload = answerBlockedHighPriority(tickets);
            session.lastIntent = INTENT_HIGH_PRIORITY;
            session.recentUserQueries.add(q);
            trimQueries(session);
            return chatResponse(
                    sessionId,
                    ASRC_LOCAL_RULE,
                    "followUpBlockedToPriority=" + INTENT_HIGH_PRIORITY,
                    payload.message,
                    payload.bullets,
                    payload.ids);
        }

        Understood understood = understandQuery(q, norm, tickets, team);
        session.recentUserQueries.add(q);
        trimQueries(session);
        session.lastIntent = intentFromUnderstood(understood);

        if (groqAIService.isEnabled() && !useLocalDataBackedAnswer(understood)) {
            log.debug("[agent-chat] Groq path understoodKind={}", understood.kind());
            try {
                return completeWithGroq(sessionId, q, session, tickets, team, understood);
            } catch (Exception e) {
                log.error("[agent-chat] Groq failed; data-backed fallback: {}", e.toString(), e);
            }
        } else if (groqAIService.isEnabled()) {
            log.debug("[agent-chat] Groq skipped for deterministic intent kind={}", understood.kind());
        } else {
            log.debug("[agent-chat] Groq disabled; data-backed reply");
        }

        return answerFromUnderstoodWithScratch(sessionId, understood, tickets, team, session);
    }

    private void touchAssigneeScratch(IntentScratchpad session, Understood u, List<Ticket> all) {
        if (u.kind() != UnderstoodKind.ASSIGNEE && u.kind() != UnderstoodKind.WORKING_ON) {
            return;
        }
        List<Ticket> m = filterTicketsForPerson(u.entityFragment(), all);
        session.lastReferencedTicketIds.clear();
        session.lastReferencedTicketIds.addAll(
                m.stream().map(Ticket::getId).filter(id -> id != null && !id.isBlank()).collect(Collectors.toList()));
        session.lastAssigneeFragment = u.entityFragment();
    }

    private AgentChatResponseDto answerFromUnderstoodWithScratch(
            String sessionId, Understood u, List<Ticket> tickets, TeamAnalyticsResponseDto team, IntentScratchpad session) {
        AgentChatResponseDto resp = answerFromUnderstood(sessionId, u, tickets, team);
        touchAssigneeScratch(session, u, tickets);
        return resp;
    }

    private static String formatAssistantForPersistence(AgentChatResponseDto resp) {
        if (resp == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String msg = resp.getMessage() != null ? resp.getMessage() : "";
        sb.append(msg);
        if (resp.getBullets() != null && !resp.getBullets().isEmpty()) {
            for (String b : resp.getBullets()) {
                sb.append('\n').append(b);
            }
        }
        return sb.toString().trim();
    }

    /** Short display title from the first user message (max ~40 chars). */
    static String shortenTitleForSession(String message) {
        if (message == null) {
            return ChatSessionStore.DEFAULT_TITLE;
        }
        String s = message.trim();
        if (s.endsWith("?")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        s = s.replaceAll("(?i)^(what are|what is|what's|whats|show me|show|list|please)\\s+", "");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() > 40) {
            s = s.substring(0, 40).trim();
            int sp = s.lastIndexOf(' ');
            if (sp > 18) {
                s = s.substring(0, sp);
            }
        }
        return s.isBlank() ? ChatSessionStore.DEFAULT_TITLE : s;
    }

    private static void trimQueries(IntentScratchpad session) {
        while (session.recentUserQueries.size() > 10) {
            session.recentUserQueries.remove(0);
        }
    }

    private AgentChatResponseDto emptyQueryPrompt(String sessionId) {
        return chatResponse(
                sessionId,
                ASRC_PROMPT,
                null,
                "Ask a portfolio question to get started — I answer from the same ticket and team snapshot as your dashboard.",
                List.of(),
                List.of());
    }

    /** Lowercase, collapse punctuation to spaces for keyword / intent detection. */
    private static String normalizeQueryText(String q) {
        return q.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    private static boolean matchesBlockedQueryNorm(String norm) {
        if (!norm.contains("blocked") && !norm.contains("blocker") && !norm.contains("blockers")) {
            return false;
        }
        if (norm.contains("not blocked") || norm.contains("unblocked") || norm.contains("non blocked")) {
            return false;
        }
        return norm.contains("ticket")
                || norm.contains("tickets")
                || norm.contains("show")
                || norm.contains("list")
                || norm.contains("what")
                || norm.contains("which")
                || norm.contains("all")
                || norm.contains("any")
                || norm.contains("work")
                || norm.contains("items")
                || norm.matches("^blocked.*")
                || norm.matches(".*\\b(show|list)\\s+blocked\\b.*");
    }

    private static boolean matchesProjectDelayWhy(String norm) {
        if (!norm.contains("why") && !norm.contains("how come")) {
            return false;
        }
        return norm.contains("delay")
                || norm.contains("delayed")
                || norm.contains("behind")
                || norm.contains("late")
                || norm.contains("slow")
                || norm.contains("slip")
                || norm.contains("project")
                || norm.contains("portfolio")
                || norm.contains("delivery");
    }

    /** "What should we do now" style — portfolio-level recommended actions from ticket rows. */
    private static boolean matchesNextActionsQuery(String norm) {
        if (norm.contains("what should we do") || norm.contains("what should i do")) {
            return true;
        }
        if (norm.contains("what should") && norm.contains("now")) {
            return true;
        }
        if (norm.contains("what do we do") && (norm.contains("now") || norm.contains("next"))) {
            return true;
        }
        return (norm.contains("next step") || norm.contains("next action"))
                && (norm.contains("portfolio") || norm.contains("ticket") || norm.contains("team") || norm.contains("project"));
    }

    private static final Set<String> ENTITY_PROBE_STOPWORDS =
            Set.of(
                    "the",
                    "a",
                    "an",
                    "to",
                    "for",
                    "of",
                    "and",
                    "or",
                    "in",
                    "on",
                    "at",
                    "is",
                    "are",
                    "was",
                    "were",
                    "be",
                    "been",
                    "show",
                    "list",
                    "all",
                    "any",
                    "some",
                    "my",
                    "our",
                    "me",
                    "us",
                    "what",
                    "which",
                    "who",
                    "how",
                    "when",
                    "where",
                    "why",
                    "tickets",
                    "ticket",
                    "items",
                    "item",
                    "work",
                    "issues",
                    "issue",
                    "open",
                    "active",
                    "current",
                    "pipeline",
                    "board",
                    "delay",
                    "delayed",
                    "delays",
                    "bottleneck",
                    "bottlenecks",
                    "please",
                    "project",
                    "portfolio",
                    "give",
                    "get",
                    "can",
                    "you",
                    "tell",
                    "about",
                    "from",
                    "with");

    /** Bare name / short phrase that matches exactly one roster member (e.g. \"danush\" → Danush S). */
    private static String inferAssigneeEntityFromTeam(String norm, TeamAnalyticsResponseDto team) {
        if (team.getMembers() == null || team.getMembers().isEmpty()) {
            return null;
        }
        String[] parts = norm.split("\\s+");
        List<String> tokens = new ArrayList<>();
        for (String p : parts) {
            if (p.length() < 3 || ENTITY_PROBE_STOPWORDS.contains(p) || p.startsWith("sim")) {
                continue;
            }
            tokens.add(p);
        }
        tokens.sort(Comparator.comparingInt(String::length).reversed());
        for (String token : tokens) {
            List<String> hits = new ArrayList<>();
            for (TeamMemberAnalyticsDto m : team.getMembers()) {
                if (m.getName() != null && assigneeNameMatches(m.getName(), token)) {
                    hits.add(m.getName());
                }
            }
            if (hits.size() == 1) {
                return token;
            }
        }
        return null;
    }

    private static Understood understandQuery(
            String q, String norm, List<Ticket> tickets, TeamAnalyticsResponseDto team) {
        Matcher workingOn = WORKING_ON_PATTERN.matcher(q.trim());
        if (workingOn.find()) {
            String person = workingOn.group(1).trim();
            if (!person.isBlank()) {
                return new Understood(UnderstoodKind.WORKING_ON, person, true);
            }
        }

        String assignedToken = extractAssignedToToken(norm, q);
        if (assignedToken != null && !assignedToken.isBlank()) {
            return new Understood(UnderstoodKind.ASSIGNEE, assignedToken.trim(), false);
        }

        if (matchesBlockedQueryNorm(norm)) {
            return new Understood(UnderstoodKind.BLOCKED, "", false);
        }

        if (matchesWhoOverloaded(norm)) {
            return new Understood(UnderstoodKind.OVERLOADED, "", false);
        }

        if (matchesProjectDelayWhy(norm)) {
            return new Understood(UnderstoodKind.PROJECT_DELAY, "", false);
        }

        if (matchesWhatDelayed(norm)) {
            return new Understood(UnderstoodKind.DELAYED_LIST, "", false);
        }

        if (matchesRiskyTickets(norm)) {
            return new Understood(UnderstoodKind.RISK, "", false);
        }

        if (matchesCompletedTicketsQuery(norm)) {
            return new Understood(UnderstoodKind.COMPLETED, "", false);
        }

        if (matchesRealBlockersQuery(norm)) {
            return new Understood(UnderstoodKind.BLOCKERS, "", false);
        }

        if (matchesNextActionsQuery(norm)) {
            return new Understood(UnderstoodKind.NEXT_ACTIONS, "", false);
        }

        String bareAssignee = inferAssigneeEntityFromTeam(norm, team);
        if (bareAssignee != null) {
            return new Understood(UnderstoodKind.ASSIGNEE, bareAssignee, false);
        }

        return new Understood(UnderstoodKind.GENERIC, "", false);
    }

    /**
     * Intents that must be answered strictly from ticket rows (no LLM paraphrase) so assignee /
     * blocker queries never degrade into generic suggestions.
     */
    private static boolean useLocalDataBackedAnswer(Understood u) {
        return switch (u.kind()) {
            case ASSIGNEE,
                    WORKING_ON,
                    BLOCKED,
                    OVERLOADED,
                    DELAYED_LIST,
                    PROJECT_DELAY,
                    RISK,
                    BLOCKERS,
                    COMPLETED,
                    NEXT_ACTIONS -> true;
            case GENERIC -> false;
        };
    }

    private static String intentFromUnderstood(Understood u) {
        return switch (u.kind()) {
            case BLOCKED -> INTENT_BLOCKED;
            case OVERLOADED -> INTENT_OVERLOADED;
            case DELAYED_LIST -> INTENT_DELAY_LIST;
            case PROJECT_DELAY -> INTENT_DELAY;
            case RISK -> INTENT_RISK;
            case BLOCKERS -> INTENT_BLOCKERS;
            case COMPLETED -> INTENT_COMPLETED;
            case NEXT_ACTIONS -> INTENT_NEXT_ACTIONS;
            case ASSIGNEE, WORKING_ON, GENERIC -> INTENT_GENERIC;
        };
    }

    private static List<Ticket> sliceTicketsForModel(List<Ticket> all, Understood u) {
        List<Ticket> slice =
                switch (u.kind()) {
                    case ASSIGNEE, WORKING_ON -> filterTicketsForPerson(u.entityFragment(), all);
                    case BLOCKED ->
                            all.stream()
                                    .filter(t -> "Blocked".equalsIgnoreCase(nullSafe(t.getStatus()).trim()))
                                    .collect(Collectors.toList());
                    case DELAYED_LIST -> filterDelayedTicketsForSlice(all);
                    case RISK ->
                            all.stream()
                                    .filter(t -> !isDone(t))
                                    .filter(
                                            t ->
                                                    "HIGH".equalsIgnoreCase(nullSafe(t.getDeliveryRisk()))
                                                            || "MEDIUM".equalsIgnoreCase(
                                                                    nullSafe(t.getDeliveryRisk())))
                                    .collect(Collectors.toList());
                    case BLOCKERS ->
                            all.stream()
                                    .filter(t -> "Blocked".equalsIgnoreCase(nullSafe(t.getStatus()).trim()))
                                    .limit(25)
                                    .collect(Collectors.toList());
                    case COMPLETED -> all.stream().filter(AgentChatService::isDone).collect(Collectors.toList());
                    case NEXT_ACTIONS -> sliceForNextActions(all);
                    default -> all;
                };
        if (slice.size() > 42) {
            return new ArrayList<>(slice.subList(0, 42));
        }
        if (slice.isEmpty() && u.kind() != UnderstoodKind.GENERIC) {
            if (u.kind() == UnderstoodKind.ASSIGNEE || u.kind() == UnderstoodKind.WORKING_ON) {
                return List.of();
            }
            return all.stream().limit(15).collect(Collectors.toList());
        }
        if (slice.isEmpty()) {
            return all.stream().limit(40).collect(Collectors.toList());
        }
        return slice;
    }

    private static List<Ticket> sliceForNextActions(List<Ticket> all) {
        return all.stream()
                .filter(t -> !isDone(t))
                .filter(
                        t ->
                                "HIGH".equalsIgnoreCase(nullSafe(t.getDeliveryRisk()))
                                        || "MEDIUM".equalsIgnoreCase(nullSafe(t.getDeliveryRisk()))
                                        || "Blocked".equalsIgnoreCase(nullSafe(t.getStatus()).trim())
                                        || isHighPri(t))
                .sorted(
                        Comparator.comparing((Ticket t) -> !"HIGH".equalsIgnoreCase(nullSafe(t.getDeliveryRisk())))
                                .thenComparing(Ticket::getId))
                .limit(20)
                .collect(Collectors.toList());
    }

    private static List<Ticket> filterDelayedTicketsForSlice(List<Ticket> tickets) {
        return tickets.stream()
                .filter(t -> !isDone(t))
                .filter(
                        t ->
                                "Blocked".equalsIgnoreCase(nullSafe(t.getStatus()).trim())
                                        || isProgressDelayed(t)
                                        || isPrDelayed(t))
                .sorted(
                        Comparator.comparing((Ticket t) -> !"Blocked".equalsIgnoreCase(nullSafe(t.getStatus())))
                                .thenComparing(Ticket::getId))
                .limit(20)
                .collect(Collectors.toList());
    }

    private AgentChatResponseDto answerFromUnderstood(
            String sessionId, Understood u, List<Ticket> tickets, TeamAnalyticsResponseDto team) {
        AnswerPayload p =
                switch (u.kind()) {
                    case ASSIGNEE -> answerPersonTicketsFormatted(u.entityFragment(), tickets, false, team);
                    case WORKING_ON -> answerPersonTicketsFormatted(u.entityFragment(), tickets, true, team);
                    case BLOCKED -> answerBlocked(tickets);
                    case OVERLOADED -> answerOverloaded(team);
                    case DELAYED_LIST -> answerDelayedTicketsList(tickets, team);
                    case PROJECT_DELAY -> answerProjectDelay(tickets, team);
                    case RISK -> answerRisks(tickets);
                    case BLOCKERS -> answerRealBlockers(tickets, team);
                    case COMPLETED -> answerCompletedTickets(tickets, team);
                    case NEXT_ACTIONS -> answerNextActions(tickets, team);
                    case GENERIC -> answerPortfolioBrief(tickets, team);
                };
        return chatResponse(
                sessionId,
                ASRC_LOCAL_HANDLER,
                "understood=" + u.kind(),
                p.message,
                p.bullets,
                p.ids);
    }

    /**
     * True for short openers that should get a human reply, not a portfolio dump (ChatGPT-style).
     * Anything that mentions work items, assignees, or ticket-like text is excluded.
     */
    private static boolean isPureSocialGreeting(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[!?.,:;]+$", "").trim();
        if (s.isEmpty()) {
            return false;
        }
        if (s.length() > 42) {
            return false;
        }
        if (s.contains("sim-")
                || s.contains("ticket")
                || s.contains("assign")
                || s.contains("block")
                || s.contains("jira")
                || s.contains("bottleneck")
                || s.contains("overload")
                || s.contains("delay")
                || s.contains("risk")
                || s.contains("who ")
                || s.contains("which ")
                || s.contains("what ")
                || s.contains("show ")
                || s.contains("list ")
                || s.contains("how many")
                || s.contains("portfolio read")) {
            return false;
        }
        Set<String> oneWord =
                Set.of(
                        "hi",
                        "hey",
                        "hello",
                        "yo",
                        "hiya",
                        "sup",
                        "thanks",
                        "thx",
                        "cheers",
                        "ok",
                        "okay",
                        "k",
                        "bye",
                        "goodbye",
                        "cya",
                        "gm",
                        "gn",
                        "help");
        if (oneWord.contains(s)) {
            return true;
        }
        if (Set.of("thank you", "thankyou", "ty", "good morning", "good afternoon", "good evening")
                .contains(s)) {
            return true;
        }
        if (s.matches("^(hi|hello|hey|yo|hiya)\\s+(there|team|all|everyone|folks)$")) {
            return true;
        }
        if (s.matches("^good\\s+(morning|afternoon|evening|day)(\\s+all)?$")) {
            return true;
        }
        if (s.matches("^how\\s+are\\s+you$") || s.matches("^what'?s\\s+up$") || s.matches("^howdy$")) {
            return true;
        }
        if (s.matches("^(thanks|thank you)\\s+(again|so much|a lot)$")) {
            return true;
        }
        return false;
    }

    private static String smalltalkReply(String lower) {
        if ("help".equals(lower.trim())) {
            return "I answer from your **current** ticket and team snapshot. Ask about workload, blockers, assignees, delivery risk, or delays — I stay within what the data shows.";
        }
        if (lower.contains("thank") || lower.contains("thx") || lower.contains("cheers")) {
            return "You're welcome — anytime you want a read on the board, just ask.";
        }
        if (lower.contains("bye") || lower.contains("cya") || lower.contains("goodbye")) {
            return "Take care. I'll be here when you want to dig into the portfolio again.";
        }
        if (lower.contains("good morning")
                || lower.contains("good afternoon")
                || lower.contains("good evening")
                || lower.contains("gm")
                || lower.contains("gn")) {
            return "Hello — when you're ready, ask about tickets, owners, blockers, or risk and I'll read the **current** board snapshot.";
        }
        return "Hi — I'm your PMO copilot for this workspace. Ask about **tickets, assignees, delays, or risk**; I answer from the same portfolio data as your dashboard.";
    }

    /** "How will you help" / "what can you do" — conversational, no ticket dump. */
    private static boolean isMetaCapabilitiesQuestion(String lower) {
        if (lower.length() > 160) {
            return false;
        }
        if (lower.contains("sim-")) {
            return false;
        }
        if (lower.contains("assigned to")
                || lower.contains("tickets for")
                || lower.contains("ticket for")
                || lower.contains("bottleneck")
                || lower.contains("overload")
                || lower.contains("completed")
                || lower.contains("blocker")
                || lower.contains("impediment")
                || lower.contains("risky")
                || lower.contains("delayed")) {
            return false;
        }
        if (lower.contains("blocked") && lower.contains("ticket")) {
            return false;
        }
        if ((lower.contains("how will") || lower.contains("how can") || lower.contains("how u ") || lower.contains("how you "))
                && lower.contains("help")) {
            return true;
        }
        if (lower.contains("what can you do") || lower.contains("what do you do")) {
            return true;
        }
        if (lower.contains("who are you") || lower.contains("introduce yourself")) {
            return true;
        }
        return false;
    }

    private static String capabilitiesReply() {
        return "I’m your **PMO copilot** for this workspace: I read the **same live ticket + team snapshot** as your dashboard.\n\n"
                + "**I can** walk you through blockers, who’s overloaded, delayed or risky work, tickets for a person, what’s already **done**, and what to escalate next — in plain language.\n\n"
                + "Ask naturally (you don’t need perfect jargon). I’ll answer the **exact** question where the data supports it, and say so when something isn’t in the feed.";
    }

    /** Completed / closed / done ticket listing (not the generic portfolio spotlight). */
    private static boolean matchesCompletedTicketsQuery(String lower) {
        boolean doneWording =
                lower.contains("completed")
                        || lower.contains("were completed")
                        || lower.contains("have been completed")
                        || lower.contains("closed")
                        || lower.contains("finished")
                        || lower.contains("released")
                        || lower.contains("shipped")
                        || (lower.contains("done") && (lower.contains("ticket") || lower.contains("tickets")));
        if (!doneWording) {
            return false;
        }
        return lower.contains("ticket")
                || lower.contains("tickets")
                || lower.contains("what")
                || lower.contains("which")
                || lower.contains("list")
                || lower.contains("show")
                || lower.contains("any")
                || lower.contains("how many")
                || lower.contains("are there");
    }

    /** Real blockers: Jira Blocked + dependency / bottleneck pressure (not the generic spotlight list). */
    private static boolean matchesRealBlockersQuery(String lower) {
        if (lower.contains("blocker") || lower.contains("impediment")) {
            return true;
        }
        if (lower.contains("blocking")
                && (lower.contains("what") || lower.contains("who") || lower.contains("which") || lower.contains("us"))) {
            return true;
        }
        if (lower.contains("stuck")
                && (lower.contains("what") || lower.contains("why") || lower.contains("which") || lower.contains("who"))
                && !lower.contains("code review")) {
            return true;
        }
        return lower.contains("real block") || lower.contains("main block") || lower.contains("biggest block");
    }

    private static boolean matchesWhoOverloaded(String lower) {
        if (!(lower.contains("overload")
                || lower.contains("over loaded")
                || lower.contains("over-loaded")
                || lower.contains("overworked"))) {
            return false;
        }
        return lower.contains("who")
                || lower.contains("anyone")
                || lower.contains("team")
                || lower.contains("which ")
                || lower.contains("which developer")
                || lower.contains("which engineer")
                || lower.contains("which people");
    }

    private static boolean matchesWhatDelayed(String lower) {
        if (lower.contains("why")) {
            return false;
        }
        boolean delay =
                lower.contains("delayed")
                        || lower.contains("delay")
                        || lower.contains("stuck")
                        || lower.contains("behind schedule");
        if (!delay) {
            return false;
        }
        return lower.contains("what")
                || lower.contains("which")
                || lower.contains("show")
                || lower.contains("list")
                || lower.contains("tickets")
                || lower.contains("items")
                || lower.contains("work");
    }

    private static boolean matchesRiskyTickets(String lower) {
        return (lower.contains("risky") || lower.contains("at risk") || lower.contains("at-risk"))
                && (lower.contains("ticket") || lower.contains("items") || lower.contains("work") || lower.contains(
                        "which"));
    }

    /** After phrases like "assigned to", "tickets for". */
    private static String extractAssignedToToken(String lower, String q) {
        String[] phrases = {"assigned to", "tickets for", "ticket for", "owned by"};
        for (String phrase : phrases) {
            int idx = lower.indexOf(phrase);
            if (idx >= 0) {
                String rest = q.substring(idx + phrase.length()).trim();
                if (rest.endsWith("?")) {
                    rest = rest.substring(0, rest.length() - 1).trim();
                }
                return rest.isBlank() ? null : rest;
            }
        }
        Matcher m = Pattern.compile("(?i)assignee\\s*(?:is|:)\\s*(.+)").matcher(q.trim());
        if (m.find()) {
            String g = m.group(1).trim();
            if (g.endsWith("?")) {
                g = g.substring(0, g.length() - 1).trim();
            }
            return g.isBlank() ? null : g;
        }
        Matcher possessive =
                Pattern.compile("(?i)([a-z][a-z'.\\s-]{1,48})'s\\s+tickets?").matcher(q.trim());
        if (possessive.find()) {
            String name = possessive.group(1).trim();
            return name.isBlank() ? null : name;
        }
        Matcher showPersonTickets =
                Pattern.compile("(?i)(?:show|list|give)\\s+([a-z][a-z'.\\s-]{1,48})\\s+tickets?")
                        .matcher(q.trim());
        if (showPersonTickets.find()) {
            String name = showPersonTickets.group(1).trim();
            return name.isBlank() ? null : name;
        }
        Matcher forPerson =
                Pattern.compile("(?i)\\btickets?\\s+for\\s+([a-z][a-z'.\\s-]{1,48})").matcher(q.trim());
        if (forPerson.find()) {
            String name = forPerson.group(1).trim();
            return name.isBlank() ? null : name;
        }
        return null;
    }

    /** Case-insensitive assignee filter (partial token match across name parts). */
    public List<Ticket> filterTicketsByAssignee(String name, List<Ticket> tickets) {
        if (name == null || name.isBlank() || tickets == null) {
            return List.of();
        }
        return filterTicketsForPerson(name.trim(), tickets);
    }

    /** True if assignee full name matches the search fragment (case-insensitive, partial, token-aware). */
    static boolean assigneeNameMatches(String assignee, String nameFragment) {
        if (assignee == null || nameFragment == null) {
            return false;
        }
        String a = assignee.trim().toLowerCase(Locale.ROOT);
        String f = nameFragment.trim().toLowerCase(Locale.ROOT);
        if (a.isEmpty() || f.isEmpty()) {
            return false;
        }
        if ("unassigned".equalsIgnoreCase(a)) {
            return false;
        }
        if (a.contains(f)) {
            return true;
        }
        String[] parts = a.split("\\s+");
        for (String p : parts) {
            if (p.length() >= 2 && (p.contains(f) || f.contains(p))) {
                return true;
            }
        }
        String[] fragTok = f.split("\\s+");
        if (fragTok.length > 1) {
            boolean all = true;
            for (String tok : fragTok) {
                if (tok.length() < 2) {
                    continue;
                }
                if (!a.contains(tok)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    private static List<Ticket> filterTicketsForPerson(String nameFragment, List<Ticket> tickets) {
        return tickets.stream()
                .filter(t -> assigneeNameMatches(t.getAssignee(), nameFragment))
                .filter(t -> !isDone(t))
                .sorted(Comparator.comparing(Ticket::getId))
                .collect(Collectors.toList());
    }

    private static String displayAssigneeFromTickets(List<Ticket> matches, String fallbackFragment) {
        return matches.stream()
                .map(Ticket::getAssignee)
                .filter(a -> a != null && !a.isBlank())
                .findFirst()
                .orElse(fallbackFragment);
    }

    private static String pickManagerInsightSentence(List<Ticket> matches) {
        Ticket blocked =
                matches.stream()
                        .filter(t -> "Blocked".equalsIgnoreCase(nullSafe(t.getStatus()).trim()))
                        .findFirst()
                        .orElse(null);
        if (blocked != null) {
            String dep = firstNonBlank(blocked.getDependency(), blocked.getBottleneckCategory());
            String root = firstNonBlank(blocked.getRootCause(), blocked.getInsight());
            if (!dep.isBlank() && !root.isBlank()) {
                return "The **" + shortSummary(blocked) + "** line is blocked — **" + dep + "**: " + root;
            }
            if (!root.isBlank()) {
                return root;
            }
            if (!dep.isBlank()) {
                return "One blocked item depends on **" + dep + "** — worth a quick escalation path if it slips further.";
            }
        }
        Ticket hot =
                matches.stream()
                        .filter(AgentChatService::isHighPri)
                        .findFirst()
                        .orElse(matches.isEmpty() ? null : matches.get(0));
        if (hot != null) {
            String rec = firstNonBlank(hot.getRecommendedAction(), hot.getInsight());
            if (!rec.isBlank()) {
                return rec;
            }
        }
        return "";
    }

    private static AnswerPayload answerPersonTicketsFormatted(
            String nameFragment, List<Ticket> tickets, boolean workingOnWording) {
        return answerPersonTicketsFormatted(nameFragment, tickets, workingOnWording, null);
    }

    private static String resolveDisplayNameForFragment(
            String nameFragment, TeamAnalyticsResponseDto team) {
        if (team == null || team.getMembers() == null) {
            return nameFragment;
        }
        for (TeamMemberAnalyticsDto m : team.getMembers()) {
            if (m.getName() != null && assigneeNameMatches(m.getName(), nameFragment)) {
                return m.getName();
            }
        }
        return nameFragment;
    }

    private static AnswerPayload answerPersonTicketsFormatted(
            String nameFragment,
            List<Ticket> tickets,
            boolean workingOnWording,
            TeamAnalyticsResponseDto team) {
        List<Ticket> matches = filterTicketsForPerson(nameFragment, tickets);
        String displayName = displayAssigneeFromTickets(matches, resolveDisplayNameForFragment(nameFragment, team));
        if (matches.isEmpty()) {
            String label = displayName != null && !displayName.isBlank() ? displayName : nameFragment.trim();
            String msg = "No tickets found for **" + label + "** in current dataset.";
            return new AnswerPayload(msg, List.of(), List.of());
        }
        List<String> ids = matches.stream().map(Ticket::getId).collect(Collectors.toList());
        List<String> bullets = new ArrayList<>();
        for (Ticket t : matches) {
            String pri = nullSafe(t.getPriority());
            String rcHint = "";
            if (t.getRootCauseAnalysis() != null && t.getRootCauseAnalysis().getPrimaryCause() != null) {
                rcHint = " (" + t.getRootCauseAnalysis().getPrimaryCause().trim() + ")";
            }
            bullets.add(
                    "• **"
                            + t.getId()
                            + "** — "
                            + shortSummary(t)
                            + " (**"
                            + nullSafe(t.getStatus())
                            + "**, "
                            + (pri.isBlank() ? "—" : pri)
                            + " priority)"
                            + rcHint);
        }
        String head =
                workingOnWording
                        ? "**"
                                + displayName
                                + "** is currently focused on **"
                                + matches.size()
                                + "** active ticket(s):"
                        : "**"
                                + displayName
                                + "** is currently working on **"
                                + matches.size()
                                + "** ticket(s):";
        String insight = pickManagerInsightSentence(matches);
        String msg = head + (insight.isBlank() ? "" : "\n\n" + insight);
        return new AnswerPayload(msg, bullets, ids);
    }

    /** Concrete delayed / stuck items: blocked, long PR, or explicit progress delay label. */
    private static AnswerPayload answerDelayedTicketsList(List<Ticket> tickets, TeamAnalyticsResponseDto team) {
        List<Ticket> delayed =
                tickets.stream()
                        .filter(t -> !isDone(t))
                        .filter(
                                t ->
                                        "Blocked".equalsIgnoreCase(nullSafe(t.getStatus()).trim())
                                                || isProgressDelayed(t)
                                                || isPrDelayed(t))
                        .sorted(
                                Comparator.comparing((Ticket t) -> !"Blocked".equalsIgnoreCase(nullSafe(t.getStatus())))
                                        .thenComparing(Ticket::getId))
                        .limit(12)
                        .collect(Collectors.toList());
        List<String> ids = delayed.stream().map(Ticket::getId).collect(Collectors.toList());
        List<String> bullets = new ArrayList<>();
        for (Ticket t : delayed) {
            bullets.add(
                    "• **"
                            + t.getId()
                            + "** – "
                            + shortSummary(t)
                            + " (**"
                            + nullSafe(t.getStatus())
                            + "**) — "
                            + nullSafe(t.getAssignee()));
        }
        String msg =
                delayed.isEmpty()
                        ? "Nothing stands out as **delayed** in the signals we have — **"
                                + team.getOverview().getTotalActiveTickets()
                                + "** active tickets overall."
                        : "Here is work that looks **delayed or stuck** from status, dwell, and PR signals (**"
                                + delayed.size()
                                + "** items). I'd triage blocked and long-in-review first.";
        return new AnswerPayload(msg, bullets, ids);
    }

    private static boolean isProgressDelayed(Ticket t) {
        String p = t.getProgressLabel();
        return p != null && p.toLowerCase(Locale.ROOT).contains("delay");
    }

    private static boolean isPrDelayed(Ticket t) {
        Double age = t.getPrAgeHours();
        Double rev = t.getReviewerDelayHours();
        boolean a = age != null && age >= 48;
        boolean r = rev != null && rev >= 48;
        return a || r || t.getPrTime() >= 48;
    }

    private static AnswerPayload answerPortfolioBrief(List<Ticket> tickets, TeamAnalyticsResponseDto team) {
        long active = tickets.stream().filter(t -> !isDone(t)).count();
        long blocked =
                tickets.stream()
                        .filter(t -> "Blocked".equalsIgnoreCase(nullSafe(t.getStatus()).trim()))
                        .count();
        List<Ticket> spotlight =
                tickets.stream()
                        .filter(t -> !isDone(t))
                        .filter(
                                t ->
                                        "Blocked".equalsIgnoreCase(nullSafe(t.getStatus()))
                                                || isHighPri(t)
                                                || "HIGH".equalsIgnoreCase(nullSafe(t.getDeliveryRisk())))
                        .sorted(Comparator.comparing(Ticket::getId))
                        .limit(6)
                        .collect(Collectors.toList());
        List<String> ids = spotlight.stream().map(Ticket::getId).collect(Collectors.toList());
        List<String> bullets = new ArrayList<>();
        for (Ticket t : spotlight) {
            bullets.add(
                    "• **"
                            + t.getId()
                            + "** — "
                            + nullSafe(t.getAssignee())
                            + " — "
                            + nullSafe(t.getStatus())
                            + " — "
                            + shortSummary(t));
        }
        String msg =
                "Portfolio snapshot: **"
                        + active
                        + "** active tickets and **"
                        + blocked
                        + "** in **Blocked**. "
                        + "Highest-signal items right now:";
        return new AnswerPayload(msg, bullets, ids);
    }

    private static AnswerPayload answerCompletedTickets(List<Ticket> tickets, TeamAnalyticsResponseDto team) {
        List<Ticket> done =
                tickets.stream()
                        .filter(AgentChatService::isDone)
                        .sorted(Comparator.comparing(Ticket::getId).reversed())
                        .limit(18)
                        .collect(Collectors.toList());
        long nDone = tickets.stream().filter(AgentChatService::isDone).count();
        List<String> ids = done.stream().map(Ticket::getId).collect(Collectors.toList());
        List<String> bullets = new ArrayList<>();
        for (Ticket t : done) {
            bullets.add(
                    "• **"
                            + t.getId()
                            + "** – "
                            + shortSummary(t)
                            + " — "
                            + nullSafe(t.getAssignee()));
        }
        int reported = team.getOverview().getCompletedTickets();
        String msg =
                done.isEmpty()
                        ? "I don’t see any tickets in **Done** in the current snapshot."
                        : "Here are **completed** tickets from this portfolio (**"
                                + nDone
                                + "** in **Done** in the data; dashboard summary reports **"
                                + reported
                                + "** completed). Showing the most recent IDs first:";
        return new AnswerPayload(msg, bullets, ids);
    }

    /** Blocked status + other non-done work with strong bottleneck / dependency signals. */
    private static AnswerPayload answerRealBlockers(List<Ticket> tickets, TeamAnalyticsResponseDto team) {
        List<Ticket> blocked =
                tickets.stream()
                        .filter(t -> "Blocked".equalsIgnoreCase(nullSafe(t.getStatus()).trim()))
                        .sorted(Comparator.comparing(Ticket::getId))
                        .collect(Collectors.toList());
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> bullets = new ArrayList<>();
        for (Ticket t : blocked.stream().limit(10).collect(Collectors.toList())) {
            seen.add(t.getId());
            bullets.add(
                    "• **"
                            + t.getId()
                            + "** (Blocked) — "
                            + shortSummary(t)
                            + " — "
                            + nullSafe(t.getAssignee())
                            + (t.getBottleneckCategory() != null && !t.getBottleneckCategory().isBlank()
                                    ? " — bottleneck: " + t.getBottleneckCategory()
                                    : ""));
        }
        List<Ticket> processPressure =
                tickets.stream()
                        .filter(t -> !isDone(t))
                        .filter(t -> !seen.contains(t.getId()))
                        .filter(AgentChatService::hasMeaningfulBottleneckOrDependency)
                        .filter(t -> isHighPri(t) || "HIGH".equalsIgnoreCase(nullSafe(t.getDeliveryRisk())))
                        .sorted(Comparator.comparing(Ticket::getId))
                        .limit(6)
                        .collect(Collectors.toList());
        for (Ticket t : processPressure) {
            bullets.add(
                    "• **"
                            + t.getId()
                            + "** (**"
                            + nullSafe(t.getStatus())
                            + "**) — "
                            + shortSummary(t)
                            + " — "
                            + nullSafe(t.getAssignee())
                            + " — "
                            + firstNonBlank(t.getBottleneckCategory(), t.getDependency()));
        }
        List<String> ids =
                bullets.stream()
                        .map(AgentChatService::extractFirstSimIdFromBullet)
                        .filter(id -> id != null)
                        .distinct()
                        .collect(Collectors.toList());
        String msg =
                blocked.isEmpty() && processPressure.isEmpty()
                        ? "Nothing is in **Blocked** right now, and I don’t see other high-signal bottleneck rows to call out — **"
                                + team.getOverview().getTotalActiveTickets()
                                + "** active tickets overall."
                        : "**Real blockers** here are tickets in **Blocked** first, then high-priority work where bottleneck or dependency labels explain why flow is stalling. I’d align owners on the Blocked row before chasing newer noise.";
        return new AnswerPayload(msg, bullets, ids.size() > 12 ? ids.subList(0, 12) : ids);
    }

    private static boolean hasMeaningfulBottleneckOrDependency(Ticket t) {
        String b = t.getBottleneckCategory();
        if (b != null && !b.isBlank() && !"None".equalsIgnoreCase(b.trim())) {
            return true;
        }
        String d = t.getDependency();
        return d != null && !d.isBlank() && !"NONE".equalsIgnoreCase(d.trim());
    }

    private static String extractFirstSimIdFromBullet(String bullet) {
        Matcher m = TICKET_ID_PATTERN.matcher(bullet);
        return m.find() ? m.group() : null;
    }

    private static String buildTeamSummaryLine(TeamAnalyticsResponseDto team) {
        StringBuilder sb = new StringBuilder();
        sb.append("members=").append(team.getOverview().getTotalTeamMembers());
        sb.append(", activeTickets=").append(team.getOverview().getTotalActiveTickets());
        sb.append(", completed=").append(team.getOverview().getCompletedTickets());
        sb.append(", inReview=").append(team.getOverview().getTicketsUnderReview());
        sb.append(". Members: ");
        for (TeamMemberAnalyticsDto m : team.getMembers()) {
            sb.append(m.getName())
                    .append(" (assigned ")
                    .append(m.getTotalTicketsAssigned())
                    .append(", blocked ")
                    .append(m.getBlocked())
                    .append("); ");
        }
        return sb.toString().trim();
    }

    private static String buildBottleneckSummaryText(List<Ticket> tickets) {
        Map<String, Long> bn = new LinkedHashMap<>();
        for (Ticket t : tickets) {
            if (isDone(t)) {
                continue;
            }
            String b = t.getBottleneckCategory() != null ? t.getBottleneckCategory() : "Unspecified";
            bn.merge(b, 1L, Long::sum);
        }
        if (bn.isEmpty()) {
            return "No open bottleneck labels.";
        }
        return bn.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> e.getKey() + ": " + e.getValue() + " ticket(s)")
                .collect(Collectors.joining("\n"));
    }

    private static List<String> extractTicketIdsFromText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        Matcher m = TICKET_ID_PATTERN.matcher(text);
        while (m.find()) {
            set.add(m.group());
        }
        return new ArrayList<>(set);
    }

    /**
     * Keeps only IDs that exist in the current ticket list so the UI does not show bogus references.
     */
    private static List<String> mergeTicketIds(List<String> fromText, List<Ticket> tickets) {
        Set<String> valid = tickets.stream().map(Ticket::getId).collect(Collectors.toSet());
        List<String> out = new ArrayList<>();
        for (String id : fromText) {
            if (valid.contains(id)) {
                out.add(id);
            }
        }
        return out.size() > 12 ? out.subList(0, 12) : out;
    }

    private String buildProjectContextJson(List<Ticket> tickets, TeamAnalyticsResponseDto team) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode tix = root.putArray("tickets");
        for (Ticket t : tickets) {
            ObjectNode n = tix.addObject();
            n.put("id", nullSafe(t.getId()));
            n.put("summary", nullSafe(t.getSummary()));
            n.put("status", nullSafe(t.getStatus()));
            n.put("assignee", nullSafe(t.getAssignee()));
            n.put("priority", nullSafe(t.getPriority()));
            n.put("deliveryRisk", nullSafe(t.getDeliveryRisk()));
            n.put("bottleneck", nullSafe(t.getBottleneckCategory()));
            n.put("timeInStateHours", t.getTimeInState());
            n.put("totalTatHours", t.getTotalTat());
            n.put("prReviewHours", t.getPrTime());
            if (t.getStageDurations() != null && !t.getStageDurations().isEmpty()) {
                ObjectNode st = n.putObject("stageDurations");
                for (Map.Entry<String, Integer> e : t.getStageDurations().entrySet()) {
                    st.put(e.getKey(), e.getValue());
                }
            }
            n.put("dependency", nullSafe(t.getDependency()));
            n.put("bounceCount", t.getBounceCount());
            n.put("insight", nullSafe(firstNonBlank(t.getInsight(), t.getReasoning())));
            n.put("rootCause", nullSafe(t.getRootCause()));
            n.put("recommendedAction", nullSafe(t.getRecommendedAction()));
            n.put("progressLabel", nullSafe(t.getProgressLabel()));
        }
        ObjectNode teamNode = root.putObject("team");
        ObjectNode overview = teamNode.putObject("overview");
        overview.put("members", team.getOverview().getTotalTeamMembers());
        overview.put("activeTickets", team.getOverview().getTotalActiveTickets());
        overview.put("completed", team.getOverview().getCompletedTickets());
        overview.put("inReview", team.getOverview().getTicketsUnderReview());
        ArrayNode members = teamNode.putArray("members");
        for (TeamMemberAnalyticsDto m : team.getMembers()) {
            ObjectNode mn = members.addObject();
            mn.put("name", m.getName());
            mn.put("experience", nullSafe(m.getExperience()));
            mn.put("assigned", m.getTotalTicketsAssigned());
            mn.put("completed", m.getCompletedTickets());
            mn.put("inReview", m.getUnderReview());
            mn.put("blocked", m.getBlocked());
            mn.put("performance", nullSafe(m.getPerformanceLabel()));
        }
        Map<String, Long> bn = new LinkedHashMap<>();
        for (Ticket t : tickets) {
            if (isDone(t)) {
                continue;
            }
            String b = t.getBottleneckCategory() != null ? t.getBottleneckCategory() : "Unspecified";
            bn.merge(b, 1L, Long::sum);
        }
        ArrayNode bottlenecks = root.putArray("bottlenecks");
        for (Map.Entry<String, Long> e : bn.entrySet()) {
            ObjectNode b = bottlenecks.addObject();
            b.put("label", e.getKey());
            b.put("count", e.getValue());
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("buildProjectContextJson failed: {}", e.getMessage());
            return "{}";
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null ? b : "";
    }

    private record GroqParsed(String message, List<String> bullets, List<String> idsFromText) {}

    private static GroqParsed parseGroqAnswer(String raw) {
        List<String> lines = List.of(raw.split("\\R"));
        List<String> bullets = new ArrayList<>();
        StringBuilder prose = new StringBuilder();
        for (String line : lines) {
            String trim = line.trim();
            if (trim.startsWith("- ") || trim.startsWith("• ") || trim.matches("^\\d+\\.\\s+.*")) {
                bullets.add(trim.replaceFirst("^[-•]\\s+", "").replaceFirst("^\\d+\\.\\s+", ""));
            } else if (!trim.isEmpty()) {
                if (prose.length() > 0) {
                    prose.append("\n");
                }
                prose.append(trim);
            }
        }
        String msg = prose.toString().trim();
        if (msg.isEmpty()) {
            msg = raw.trim();
        }
        List<String> bl = bullets.size() > 8 ? bullets.subList(0, 8) : bullets;
        return new GroqParsed(msg, bl, extractTicketIdsFromText(raw));
    }

    private static boolean isFollowUpHighPriority(String q) {
        String s = q.toLowerCase(Locale.ROOT);
        if (s.contains("high priority") || s.contains("critical priority")) {
            return true;
        }
        return s.contains("what about")
                && (s.contains("priority") || s.contains("ones") || s.contains("those"));
    }

    private static AnswerPayload answerBlocked(List<Ticket> tickets) {
        List<Ticket> blocked =
                tickets.stream()
                        .filter(t -> t.getStatus() != null && "Blocked".equalsIgnoreCase(t.getStatus().trim()))
                        .sorted(Comparator.comparing(Ticket::getId))
                        .collect(Collectors.toList());
        List<String> ids = blocked.stream().map(Ticket::getId).collect(Collectors.toList());
        List<String> bullets = new ArrayList<>();
        for (Ticket t : blocked.stream().limit(8).collect(Collectors.toList())) {
            bullets.add(
                    t.getId()
                            + " — "
                            + shortSummary(t)
                            + " ("
                            + nullSafe(t.getAssignee())
                            + ")");
        }
        String msg =
                blocked.isEmpty()
                        ? "There are no tickets in **Blocked** right now."
                        : "Here are the blocked tickets I see (**"
                                + blocked.size()
                                + "**). I highlighted IDs so you can jump straight to them.";
        return new AnswerPayload(msg, bullets, ids);
    }

    private static AnswerPayload answerBlockedHighPriority(List<Ticket> tickets) {
        List<Ticket> blockedHigh =
                tickets.stream()
                        .filter(t -> t.getStatus() != null && "Blocked".equalsIgnoreCase(t.getStatus().trim()))
                        .filter(AgentChatService::isHighPri)
                        .sorted(Comparator.comparing(Ticket::getId))
                        .collect(Collectors.toList());
        List<String> ids = blockedHigh.stream().map(Ticket::getId).collect(Collectors.toList());
        List<String> bullets = new ArrayList<>();
        for (Ticket t : blockedHigh) {
            bullets.add(
                    t.getId()
                            + " — "
                            + nullSafe(t.getPriority())
                            + " — "
                            + shortSummary(t)
                            + " ("
                            + nullSafe(t.getAssignee())
                            + ")");
        }
        String msg =
                blockedHigh.isEmpty()
                        ? "None of the **blocked** tickets are high/critical priority right now."
                        : "Within **blocked** work, these are **high / critical** priority (**"
                                + blockedHigh.size()
                                + "**).";
        return new AnswerPayload(msg, bullets, ids);
    }

    private static AnswerPayload answerHighPriority(List<Ticket> tickets) {
        List<Ticket> highs =
                tickets.stream()
                        .filter(
                                t -> {
                                    String p = t.getPriority();
                                    if (p == null) {
                                        return false;
                                    }
                                    String u = p.toUpperCase(Locale.ROOT);
                                    return u.contains("HIGH") || u.contains("CRITICAL");
                                })
                        .sorted(Comparator.comparing(Ticket::getId))
                        .collect(Collectors.toList());
        List<String> ids = highs.stream().map(Ticket::getId).collect(Collectors.toList());
        List<String> bullets = new ArrayList<>();
        for (Ticket t : highs.stream().limit(10).collect(Collectors.toList())) {
            bullets.add(
                    t.getId()
                            + " — "
                            + nullSafe(t.getStatus())
                            + " — "
                            + shortSummary(t));
        }
        String msg =
                "High / critical priority issues in the portfolio (**" + highs.size() + "**).";
        return new AnswerPayload(msg, bullets, ids);
    }

    private static AnswerPayload answerTopLoad(TeamAnalyticsResponseDto team) {
        List<TeamMemberAnalyticsDto> m = team.getMembers();
        if (m.isEmpty()) {
            return new AnswerPayload("No assignees found in the current portfolio.", List.of(), List.of());
        }
        TeamMemberAnalyticsDto top =
                m.stream().max(Comparator.comparingInt(TeamMemberAnalyticsDto::getTotalTicketsAssigned)).orElseThrow();
        String msg =
                "**"
                        + top.getName()
                        + "** has the largest assigned footprint (**"
                        + top.getTotalTicketsAssigned()
                        + "** tickets assigned in Jira).";
        List<String> bullets = new ArrayList<>();
        m.stream()
                .sorted(Comparator.comparingInt(TeamMemberAnalyticsDto::getTotalTicketsAssigned).reversed())
                .limit(5)
                .forEach(
                        row ->
                                bullets.add(
                                        row.getName()
                                                + ": "
                                                + row.getTotalTicketsAssigned()
                                                + " assigned, "
                                                + row.getCompletedTickets()
                                                + " done"));
        return new AnswerPayload(msg, bullets, List.of());
    }

    private static AnswerPayload answerOverloaded(TeamAnalyticsResponseDto team) {
        List<WorkloadBarDto> bars = team.getWorkloadByAssignee();
        List<String> overloaded =
                bars.stream()
                        .filter(b -> "OVERLOADED".equalsIgnoreCase(b.getHighlight()))
                        .map(WorkloadBarDto::getAssigneeName)
                        .collect(Collectors.toList());
        List<String> bullets =
                bars.stream()
                        .map(b -> b.getAssigneeName() + ": " + b.getActiveTicketCount() + " active tickets")
                        .collect(Collectors.toList());
        String msg =
                overloaded.isEmpty()
                        ? "No one is above the team **overload** band right now — active WIP looks relatively"
                                + " balanced."
                        : "These people show **elevated active WIP** vs peers: **"
                                + String.join(", ", overloaded)
                                + "**.";
        return new AnswerPayload(msg, bullets, List.of());
    }

    private static AnswerPayload answerNoCommits(List<Ticket> tickets) {
        List<Ticket> noCommits =
                tickets.stream()
                        .filter(t -> t.getCommitCount() <= 0)
                        .filter(t -> !isDone(t))
                        .sorted(Comparator.comparing(Ticket::getId))
                        .collect(Collectors.toList());
        List<String> ids = noCommits.stream().map(Ticket::getId).collect(Collectors.toList());
        List<String> bullets = new ArrayList<>();
        for (Ticket t : noCommits.stream().limit(10).collect(Collectors.toList())) {
            bullets.add(t.getId() + " — " + shortSummary(t) + " (" + nullSafe(t.getAssignee()) + ")");
        }
        String msg = "Open tickets with **no commits** on the branch yet (**" + noCommits.size() + "**).";
        return new AnswerPayload(msg, bullets, ids);
    }

    private static AnswerPayload answerDelayedPrs(List<Ticket> tickets) {
        List<Ticket> delayed =
                tickets.stream()
                        .filter(t -> !isDone(t))
                        .filter(
                                t -> {
                                    Double age = t.getPrAgeHours();
                                    Double rev = t.getReviewerDelayHours();
                                    boolean a = age != null && age >= 48;
                                    boolean r = rev != null && rev >= 48;
                                    return a || r || t.getPrTime() >= 48;
                                })
                        .sorted(Comparator.comparing(Ticket::getId))
                        .collect(Collectors.toList());
        List<String> ids = delayed.stream().map(Ticket::getId).collect(Collectors.toList());
        List<String> bullets = new ArrayList<>();
        for (Ticket t : delayed.stream().limit(8).collect(Collectors.toList())) {
            bullets.add(
                    t.getId()
                            + " — PR age ~"
                            + fmtD(t.getPrAgeHours())
                            + "h, reviewer delay ~"
                            + fmtD(t.getReviewerDelayHours())
                            + "h");
        }
        String msg =
                "Pull requests that look **delayed** (about ≥48h PR age, reviewer delay, or long review time) — **"
                        + delayed.size()
                        + "** items.";
        return new AnswerPayload(msg, bullets, ids);
    }

    private static AnswerPayload answerNextActions(List<Ticket> tickets, TeamAnalyticsResponseDto team) {
        List<Ticket> hot =
                tickets.stream()
                        .filter(t -> !isDone(t))
                        .filter(
                                t ->
                                        "HIGH".equalsIgnoreCase(nullSafe(t.getDeliveryRisk()))
                                                || "MEDIUM".equalsIgnoreCase(nullSafe(t.getDeliveryRisk()))
                                                || "Blocked".equalsIgnoreCase(nullSafe(t.getStatus()).trim()))
                        .sorted(Comparator.comparing(Ticket::getId))
                        .limit(10)
                        .collect(Collectors.toList());
        List<String> bullets = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Ticket t : hot) {
            ids.add(t.getId());
            String act =
                    firstNonBlank(
                            t.getRecommendedAction(),
                            "Confirm the next owner and unblocker in the ticket thread.");
            bullets.add("• **" + t.getId() + "** — " + act);
        }
        String msg =
                hot.isEmpty()
                        ? "No elevated-risk items in the current dataset — nothing urgent to sequence beyond normal stand-up hygiene."
                        : "Here is what the portfolio engine recommends **doing next** for the hottest items (risk / blocked), based on ticket-level actions already computed.";
        return new AnswerPayload(msg, bullets, ids);
    }

    private static AnswerPayload answerProjectDelay(List<Ticket> tickets, TeamAnalyticsResponseDto team) {
        long highInProg =
                tickets.stream()
                        .filter(t -> !isDone(t) && isHighPri(t) && isInProgress(t))
                        .count();
        long delayedPr =
                tickets.stream()
                        .filter(t -> !isDone(t))
                        .filter(
                                t -> {
                                    Double age = t.getPrAgeHours();
                                    return age != null && age >= 48;
                                })
                        .count();
        long blocked = tickets.stream().filter(t -> "Blocked".equalsIgnoreCase(nullSafe(t.getStatus()))).count();
        Map<String, Long> primaryCounts = new LinkedHashMap<>();
        for (Ticket t : tickets) {
            if (isDone(t) || t.getRootCauseAnalysis() == null) {
                continue;
            }
            String p = t.getRootCauseAnalysis().getPrimaryCause();
            if (p == null || p.isBlank()) {
                continue;
            }
            primaryCounts.merge(p, 1L, Long::sum);
        }
        List<String> bullets = new ArrayList<>();
        bullets.add(highInProg + " high-priority tickets still **In Progress** with long dwell.");
        bullets.add(delayedPr + " items show **PR age** over ~48h.");
        bullets.add(blocked + " tickets are in **Blocked** — often external dependencies.");
        primaryCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(4)
                .forEach(e -> bullets.add("Root-cause theme **" + e.getKey() + "** → **" + e.getValue() + "** active tickets"));
        String msg =
                "The portfolio is delayed mainly due to **review dwell**, **blocked / dependency waits**, and **high-priority execution still in flight**. Engine summary: **"
                        + team.getOverview().getTotalActiveTickets()
                        + "** active tickets — see bullets for numeric split and dominant root-cause themes.";
        return new AnswerPayload(msg, bullets, List.of());
    }

    private static AnswerPayload answerBottlenecks(List<Ticket> tickets) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Ticket t : tickets) {
            if (isDone(t)) {
                continue;
            }
            String b = t.getBottleneckCategory() != null ? t.getBottleneckCategory() : "Unspecified";
            counts.merge(b, 1L, Long::sum);
        }
        List<String> bullets =
                counts.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                        .limit(6)
                        .map(e -> e.getKey() + ": **" + e.getValue() + "** open tickets")
                        .collect(Collectors.toList());
        String msg =
                "Here is a **bottleneck mix** from Jira bottleneck labels on non-done work — use it to steer"
                        + " stand-ups.";
        return new AnswerPayload(msg, bullets, List.of());
    }

    private static AnswerPayload answerRisks(List<Ticket> tickets) {
        List<Ticket> atRisk =
                tickets.stream()
                        .filter(t -> "HIGH".equalsIgnoreCase(nullSafe(t.getDeliveryRisk()))
                                || "MEDIUM".equalsIgnoreCase(nullSafe(t.getDeliveryRisk())))
                        .filter(t -> !isDone(t))
                        .sorted(Comparator.comparing(Ticket::getId))
                        .limit(8)
                        .collect(Collectors.toList());
        List<String> ids = atRisk.stream().map(Ticket::getId).collect(Collectors.toList());
        List<String> bullets = new ArrayList<>();
        for (Ticket t : atRisk) {
            bullets.add(
                    t.getId()
                            + " — delivery risk **"
                            + nullSafe(t.getDeliveryRisk())
                            + "** — "
                            + shortSummary(t));
        }
        String msg =
                "Top **delivery-risk** items (non-done work with elevated delivery risk).";
        return new AnswerPayload(msg, bullets, ids);
    }

    private static AnswerPayload answerAttention(List<Ticket> tickets) {
        List<Ticket> candidates =
                tickets.stream()
                        .filter(t -> !isDone(t))
                        .filter(
                                t ->
                                        "Blocked".equalsIgnoreCase(nullSafe(t.getStatus()))
                                                || isHighPri(t)
                                                || (t.getFlags() != null && t.getFlags().contains(MetricsService.FLAG_PR_DELAY)))
                        .sorted(Comparator.comparing(Ticket::getId))
                        .limit(10)
                        .collect(Collectors.toList());
        List<String> ids = candidates.stream().map(Ticket::getId).collect(Collectors.toList());
        List<String> bullets = new ArrayList<>();
        for (Ticket t : candidates) {
            bullets.add(
                    t.getId()
                            + " — "
                            + nullSafe(t.getStatus())
                            + " / "
                            + nullSafe(t.getPriority())
                            + " — "
                            + shortSummary(t));
        }
        String msg =
                "Tickets that **need attention** first: blocked, high/critical priority, or PR-delay flagged.";
        return new AnswerPayload(msg, bullets, ids);
    }

    private static boolean isDone(Ticket t) {
        return t.getStatus() != null && "Done".equalsIgnoreCase(t.getStatus().trim());
    }

    private static boolean isInProgress(Ticket t) {
        return t.getStatus() != null && "In Progress".equalsIgnoreCase(t.getStatus().trim());
    }

    private static boolean isHighPri(Ticket t) {
        String p = t.getPriority();
        if (p == null) {
            return false;
        }
        String u = p.toUpperCase(Locale.ROOT);
        return u.contains("HIGH") || u.contains("CRITICAL");
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String shortSummary(Ticket t) {
        String s = t.getSummary();
        if (s == null) {
            return "(no summary)";
        }
        s = s.trim();
        return s.length() <= 72 ? s : s.substring(0, 69) + "...";
    }

    private static String fmtD(Double d) {
        if (d == null) {
            return "0";
        }
        return String.format(Locale.ROOT, "%.0f", d);
    }

    private static final class AnswerPayload {
        final String message;
        final List<String> bullets;
        final List<String> ids;

        AnswerPayload(String message, List<String> bullets, List<String> ids) {
            this.message = message;
            this.bullets = bullets;
            this.ids = ids;
        }
    }

    private static final class IntentScratchpad {
        String lastIntent;
        final List<String> recentUserQueries = new ArrayList<>();
        final List<String> lastReferencedTicketIds = new ArrayList<>();
        String lastAssigneeFragment;
    }
}
