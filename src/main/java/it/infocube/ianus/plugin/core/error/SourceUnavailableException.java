package it.infocube.ianus.plugin.core.error;

import org.springframework.http.HttpStatus;

/** Dati non disponibili: NMS non raggiungibile in lettura, o nessun blocco raccolto per la finestra. */
public class SourceUnavailableException extends IanusException {

    public SourceUnavailableException(String source, String detail) {
        this("NMS non raggiungibile (" + source + "): " + detail);
    }

    private SourceUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "source_unavailable", message);
    }

    /** La finestra non ha blocchi: l'NMS puo' essere raggiungibile, per esempio prima della raccolta. */
    public static SourceUnavailableException noBlocks(String source) {
        return new SourceUnavailableException("Nessun blocco raccolto per la finestra (" + source + ")");
    }
}
