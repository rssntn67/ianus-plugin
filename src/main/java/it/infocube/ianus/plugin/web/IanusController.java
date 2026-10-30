package it.infocube.ianus.plugin.web;

import java.util.List;

import it.infocube.ianus.plugin.core.model.CapabilitiesResponse;
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
public class IanusController {

    private final IanusService service;

    public IanusController(IanusService service) {
        this.service = service;
    }

    @GetMapping("/{source}/metrics")
    public MetricsResponse metrics(@PathVariable("source") String source,
                                   @RequestParam(name = "window", required = false) String window,
                                   @RequestParam(name = "end", required = false) String end,
                                   @RequestParam(name = "node", required = false) List<String> node,
                                   @RequestParam(name = "metric", required = false) List<String> metric,
                                   @RequestParam(name = "page", required = false) Integer page,
                                   @RequestParam(name = "size", required = false) Integer size) {
        return service.metrics(source, window, end, node, metric, page, size);
    }

    @GetMapping("/{source}/events")
    public EventsResponse events(@PathVariable("source") String source,
                                 @RequestParam(name = "window", required = false) String window,
                                 @RequestParam(name = "end", required = false) String end,
                                 @RequestParam(name = "node", required = false) List<String> node,
                                 @RequestParam(name = "page", required = false) Integer page,
                                 @RequestParam(name = "size", required = false) Integer size) {
        return service.events(source, window, end, node, page, size);
    }

    @GetMapping("/{source}/capabilities")
    public CapabilitiesResponse capabilities(@PathVariable("source") String source) {
        return service.capabilities(source);
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return service.health();
    }
}
