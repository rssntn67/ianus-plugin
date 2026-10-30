package it.infocube.ianus.plugin.core.model;

public record SourceHealth(String system, String id, boolean reachable, String message) {
}
