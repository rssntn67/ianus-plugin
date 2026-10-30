package it.infocube.ianus.plugin.core.error;

import org.springframework.http.HttpStatus;

/** Errore applicativo con codice HTTP e codice simbolico restituiti a IANUS. */
public class IanusException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public IanusException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
