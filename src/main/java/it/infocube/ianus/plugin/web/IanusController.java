package it.infocube.ianus.plugin.web;

import java.util.List;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import it.infocube.ianus.plugin.core.model.CapabilitiesResponse;
import it.infocube.ianus.plugin.core.model.ErrorResponse;
import it.infocube.ianus.plugin.core.model.EventsResponse;
import it.infocube.ianus.plugin.core.model.HealthResponse;
import it.infocube.ianus.plugin.core.model.MetricsResponse;
import it.infocube.ianus.plugin.core.service.IanusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Contratto REST v2 (sezione 7.2). */
@RestController
@RequestMapping("/ianus/v2")
@SecurityRequirement(name = "apiKey")
@ApiResponse(responseCode = "401", description = "API key assente o non valida",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
public class IanusController {

    private final IanusService service;

    public IanusController(IanusService service) {
        this.service = service;
    }

    @ApiResponse(responseCode = "200", description = "Media, minimo e massimo di ogni serie nella finestra",
            useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "Finestra non valida o troppo vecchia, parametri non validi",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Sorgente non configurata",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "Nessun blocco raccolto per la finestra",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{source}/metrics")
    public MetricsResponse metrics(@PathVariable("source") String source,
                                   @Parameter(required = true)
                                   @RequestParam(name = "window", required = false) String window,
                                   @RequestParam(name = "end", required = false) String end,
                                   @RequestParam(name = "node", required = false) List<String> node,
                                   @RequestParam(name = "metric", required = false) List<String> metric,
                                   @RequestParam(name = "page", required = false) Integer page,
                                   @RequestParam(name = "size", required = false) Integer size) {
        return service.metrics(source, window, end, node, metric, page, size);
    }

    @ApiResponse(responseCode = "200", description = "Eventi della finestra raggruppati", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "Finestra non valida o troppo vecchia, parametri non validi",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Sorgente non configurata",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "Nessun blocco raccolto per la finestra",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{source}/events")
    public EventsResponse events(@PathVariable("source") String source,
                                 @Parameter(required = true)
                                 @RequestParam(name = "window", required = false) String window,
                                 @RequestParam(name = "end", required = false) String end,
                                 @RequestParam(name = "node", required = false) List<String> node,
                                 @RequestParam(name = "page", required = false) Integer page,
                                 @RequestParam(name = "size", required = false) Integer size) {
        return service.events(source, window, end, node, page, size);
    }

    @ApiResponse(responseCode = "200", description = "Finestre, metriche, nodi e profondita' storica",
            useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Sorgente non configurata",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{source}/capabilities")
    public CapabilitiesResponse capabilities(@PathVariable("source") String source) {
        return service.capabilities(source);
    }

    @ApiResponse(responseCode = "200", description = "Stato del plugin e di ogni NMS", useReturnTypeSchema = true)
    @GetMapping("/health")
    public HealthResponse health() {
        return service.health();
    }
}
