package com.aipmo.agent.service;

import com.aipmo.agent.model.Ticket;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure simulation helpers for smart-commit / branch / PR → ticket keys (SIM-####). No Git network
 * calls.
 */
@Service
public class GitLinkingService {

    public static final Pattern SIM_TICKET_KEY = Pattern.compile("SIM-\\d+");

    /**
     * Parses the first synthetic ticket key from a commit subject/body line (e.g. {@code
     * SIM-1013 fix edge case}).
     */
    public Optional<String> extractTicketKeyFromCommit(String commitMessage) {
        if (commitMessage == null || commitMessage.isBlank()) {
            return Optional.empty();
        }
        Matcher m = SIM_TICKET_KEY.matcher(commitMessage);
        return m.find() ? Optional.of(m.group()) : Optional.empty();
    }

    /**
     * Resolves ticket id from a branch name when it embeds {@code SIM-NNNN}, e.g. {@code
     * feature/SIM-1013-login}.
     */
    public Optional<String> extractTicketFromBranch(String branchName) {
        if (branchName == null || branchName.isBlank()) {
            return Optional.empty();
        }
        Matcher m = SIM_TICKET_KEY.matcher(branchName);
        return m.find() ? Optional.of(m.group()) : Optional.empty();
    }

    /** Same rule as branch — PR titles typically include the key. */
    public Optional<String> extractTicketFromPrTitle(String prTitle) {
        return extractTicketFromBranch(prTitle);
    }

    /** Ticket id → synthetic commit messages (demo index). */
    public Map<String, List<String>> commitsByTicketId(List<Ticket> tickets) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        if (tickets == null) {
            return map;
        }
        for (Ticket t : tickets) {
            if (t.getId() == null || t.getCommitMessages() == null || t.getCommitMessages().isEmpty()) {
                continue;
            }
            map.put(t.getId(), new ArrayList<>(t.getCommitMessages()));
        }
        return map;
    }
}
