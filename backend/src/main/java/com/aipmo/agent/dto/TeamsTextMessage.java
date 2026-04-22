package com.aipmo.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TeamsTextMessage(@JsonProperty("text") String text) {}
