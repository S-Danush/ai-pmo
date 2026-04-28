package com.aipmo.agent.controller;

import com.aipmo.agent.dto.ManualNotifyResponse;
import com.aipmo.agent.logging.PipelineMdc;
import com.aipmo.agent.service.TicketNotifyService;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api")
public class NotifyController {

    private final TicketNotifyService ticketNotifyService;

    public NotifyController(TicketNotifyService ticketNotifyService) {
        this.ticketNotifyService = ticketNotifyService;
    }

    @PostMapping("/notify/{ticketId}")
    public ManualNotifyResponse notify(@PathVariable("ticketId") String ticketId) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(PipelineMdc.KEY_REQUEST_ID, requestId);
        MDC.put(PipelineMdc.KEY_SIMULATION, "true");
        try {
            return ticketNotifyService.notifyTicket(ticketId);
        } finally {
            MDC.clear();
        }
    }
}
