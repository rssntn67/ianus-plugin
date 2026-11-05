package it.infocube.ianus.plugin.web;

import java.util.Locale;

import it.infocube.ianus.plugin.core.error.IanusException;
import it.infocube.ianus.plugin.core.model.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Corpo d'errore con almeno code e message (sezione 7.4). */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IanusException.class)
    public ResponseEntity<ErrorResponse> ianus(IanusException e) {
        if (e.status().is5xxServerError()) {
            LOG.warn("{}: {}", e.code(), e.getMessage());
        }
        return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("invalid_parameter", "Parametro non valido: " + e.getName()));
    }

    /** Errori di Spring MVC (percorso inesistente, metodo non ammesso) e imprevisti, sempre con code e message. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse mvc) {
            HttpStatus status = HttpStatus.valueOf(mvc.getStatusCode().value());
            return ResponseEntity.status(status).headers(mvc.getHeaders())
                    .body(new ErrorResponse(status.name().toLowerCase(Locale.ROOT),
                            "Richiesta non gestita: " + status.getReasonPhrase()));
        }
        LOG.error("Errore imprevisto", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("internal_error", "Errore interno del plugin"));
    }
}
