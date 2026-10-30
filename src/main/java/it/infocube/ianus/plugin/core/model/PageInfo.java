package it.infocube.ianus.plugin.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PageInfo(int number, int size, @JsonProperty("total_elements") long totalElements) {
}
