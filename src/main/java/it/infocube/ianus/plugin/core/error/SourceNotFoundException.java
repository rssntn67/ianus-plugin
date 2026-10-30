package it.infocube.ianus.plugin.core.error;

import org.springframework.http.HttpStatus;

/** Sorgente non configurata nel plugin. */
public class SourceNotFoundException extends IanusException {

    public SourceNotFoundException(String source) {
        super(HttpStatus.NOT_FOUND, "source_not_found", "Sorgente non configurata: " + source);
    }
}
