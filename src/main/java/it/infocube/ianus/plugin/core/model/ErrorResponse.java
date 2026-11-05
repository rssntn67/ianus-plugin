package it.infocube.ianus.plugin.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ErrorResponse(
        @JsonProperty(required = true) String code,
        @JsonProperty(required = true) String message) {
}
