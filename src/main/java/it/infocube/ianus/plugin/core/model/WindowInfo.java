package it.infocube.ianus.plugin.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WindowInfo(
        @JsonProperty(required = true) String start,
        @JsonProperty(required = true) String end,
        @JsonProperty(required = true) String duration) {
}
