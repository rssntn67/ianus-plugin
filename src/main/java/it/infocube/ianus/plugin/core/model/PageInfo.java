package it.infocube.ianus.plugin.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PageInfo(
        @JsonProperty(required = true) int number,
        @JsonProperty(required = true) int size,
        @JsonProperty(value = "total_elements", required = true) long totalElements) {
}
