package it.infocube.ianus.plugin.core.error;

import org.springframework.http.HttpStatus;

/** NMS non raggiungibile: nessun dato restituito. */
public class SourceUnavailableException extends IanusException {

    public SourceUnavailableException(String source, String detail) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "source_unavailable",
                "NMS non raggiungibile (" + source + "): " + detail);
    }
}
