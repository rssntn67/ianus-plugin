package it.infocube.ianus.plugin.core.error;

import org.springframework.http.HttpStatus;

/** Parametri non validi: finestra non supportata, end non allineato o nel futuro, paginazione errata. */
public class BadRequestException extends IanusException {

    public BadRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
