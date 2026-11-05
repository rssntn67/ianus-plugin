package it.infocube.ianus.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import it.infocube.ianus.plugin.core.source.SourceAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Avvio completo del contesto con tutte le sorgenti abilitate e NMS irraggiungibili:
 * il plugin deve partire, rispondere su HTTP e segnalare le sorgenti come non raggiungibili.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ianus.api-key=test-key",
        "ianus.sources.opennms.enabled=true",
        "ianus.sources.opennms.url=http://127.0.0.1:1/opennms",
        "ianus.sources.prometheus.enabled=true",
        "ianus.sources.prometheus.url=http://127.0.0.1:1",
        "ianus.sources.prometheus.metrics[0].name=cpu_usage",
        "ianus.sources.icinga.enabled=true",
        "ianus.sources.icinga.url=https://127.0.0.1:1",
        "ianus.sources.icinga.poll-interval=PT1H",
        "ianus.sources.zabbix.enabled=true",
        "ianus.sources.zabbix.url=http://127.0.0.1:1/api_jsonrpc.php"
})
@ExtendWith(OutputCaptureExtension.class)
class IanusPluginApplicationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    List<SourceAdapter> adapters;

    @Test
    void allSourcesAreRegistered() {
        assertThat(adapters).extracting(SourceAdapter::system)
                .containsExactlyInAnyOrder("opennms", "prometheus", "icinga", "zabbix");
    }

    @Test
    void healthRequiresApiKey() throws Exception {
        assertThat(get("/ianus/v2/health", null).statusCode()).isEqualTo(401);
        assertThat(get("/ianus/v2/health", "wrong").statusCode()).isEqualTo(401);
    }

    @Test
    void healthReportsUnreachableSources() throws Exception {
        HttpResponse<String> res = get("/ianus/v2/health", "test-key");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Content-Type")).hasValueSatisfying(
                v -> assertThat(v).startsWith("application/json"));
        assertThat(res.body()).contains("\"status\":\"DEGRADED\"", "\"plugin_version\":\"2.0.0\"",
                "\"reachable\":false");
    }

    @Test
    void unknownSourceIs404AndUnalignedEndIs400() throws Exception {
        HttpResponse<String> notFound = get("/ianus/v2/syslog/metrics?window=PT5M", "test-key");
        assertThat(notFound.statusCode()).isEqualTo(404);
        assertThat(notFound.body()).contains("\"code\":\"source_not_found\"");

        HttpResponse<String> bad = get("/ianus/v2/zabbix/metrics?window=PT5M&end=2026-10-14T10:03:00Z", "test-key");
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(bad.body()).contains("\"code\"", "\"message\"");
    }

    @Test
    void openApiIsPublicAndListsEndpoints() throws Exception {
        HttpResponse<String> res = get("/v3/api-docs", null);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("/ianus/v2/{source}/metrics", "/ianus/v2/{source}/events",
                "/ianus/v2/{source}/capabilities", "/ianus/v2/health");
    }

    @ParameterizedTest
    @ValueSource(strings = {"opennms", "prometheus", "icinga", "zabbix"})
    void unreachableNmsIs503(String source) throws Exception {
        for (String endpoint : List.of("metrics", "events")) {
            HttpResponse<String> res = get("/ianus/v2/" + source + "/" + endpoint + "?window=PT5M", "test-key");

            assertThat(res.statusCode()).isEqualTo(503);
            assertThat(JSON.readTree(res.body()).path("code").asString()).isEqualTo("source_unavailable");
        }
    }

    @Test
    void requestsAreLoggedWithStatusAndWithoutApiKey(CapturedOutput output) throws Exception {
        get("/ianus/v2/health?prova=1", "chiave-sbagliata");
        get("/v3/api-docs", null);

        assertThat(output.getOut()).containsPattern("GET /ianus/v2/health\\?prova=1 -> 401 in \\d+ ms")
                .doesNotContain("chiave-sbagliata").doesNotContain("GET /v3/api-docs");
    }

    @Test
    void unknownPathHasContractErrorBody() throws Exception {
        HttpResponse<String> res = get("/ianus/v2/opennms/inesistente", "test-key");

        assertThat(res.statusCode()).isEqualTo(404);
        JsonNode body = JSON.readTree(res.body());
        assertThat(body.path("code").asString()).isEqualTo("not_found");
        assertThat(body.path("message").asString()).isNotBlank();
    }

    @Test
    void wrongMethodHasContractErrorBody() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ianus/v2/health"))
                .header("X-API-Key", "test-key").POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(405);
        JsonNode body = JSON.readTree(res.body());
        assertThat(body.path("code").asString()).isEqualTo("method_not_allowed");
        assertThat(body.path("message").asString()).isNotBlank();
    }

    @Test
    void openApiDeclaresWindowAsRequired() throws Exception {
        JsonNode parameters = apiDocs().at("/paths/~1ianus~1v2~1{source}~1metrics/get/parameters");

        assertThat(parameters.valueStream().filter(p -> p.path("name").asString().equals("window")).findFirst())
                .hasValueSatisfying(p -> assertThat(p.path("required").asBoolean()).isTrue());
    }

    @Test
    void openApiDocumentsErrorResponses() throws Exception {
        JsonNode docs = apiDocs();

        assertThat(docs.at("/paths/~1ianus~1v2~1{source}~1metrics/get/responses").propertyNames())
                .contains("200", "400", "401", "404", "503");
        assertThat(docs.at("/paths/~1ianus~1v2~1{source}~1events/get/responses").propertyNames())
                .contains("200", "400", "401", "404", "503");
        assertThat(docs.at("/paths/~1ianus~1v2~1{source}~1capabilities/get/responses").propertyNames())
                .contains("200", "401", "404").doesNotContain("503");
        assertThat(docs.at("/paths/~1ianus~1v2~1health/get/responses").propertyNames()).contains("200", "401");
    }

    @Test
    void openApi503DescribesMissingData() throws Exception {
        assertThat(apiDocs().at("/paths/~1ianus~1v2~1{source}~1metrics/get/responses/503/description").asString())
                .isEqualTo("Nessun blocco raccolto per la finestra");
    }

    @Test
    void openApiDeclaresApiKeyOnEveryEndpoint() throws Exception {
        JsonNode docs = apiDocs();

        JsonNode scheme = docs.at("/components/securitySchemes/apiKey");
        assertThat(scheme.path("type").asString()).isEqualTo("apiKey");
        assertThat(scheme.path("in").asString()).isEqualTo("header");
        assertThat(scheme.path("name").asString()).isEqualTo("X-API-Key");
        for (String op : List.of("/paths/~1ianus~1v2~1{source}~1metrics/get", "/paths/~1ianus~1v2~1health/get")) {
            assertThat(docs.at(op + "/security").toString()).contains("apiKey");
        }
    }

    @Test
    void openApiDeclaresRequiredFieldsAndNullableStats() throws Exception {
        JsonNode series = apiDocs().at("/components/schemas/MetricSeries");

        assertThat(series.path("required").valueStream().map(JsonNode::asString))
                .containsExactlyInAnyOrder("node", "metric", "instance", "unit", "value", "min", "max", "samples");
        assertThat(series.at("/properties/value/type").toString()).contains("null");

        assertThat(apiDocs().at("/components/schemas/EventGroup/required").valueStream().map(JsonNode::asString))
                .containsExactlyInAnyOrder("node", "event_id", "instance", "severity", "native_severity", "count",
                        "first_seen", "last_seen");
    }

    private JsonNode apiDocs() throws Exception {
        return JSON.readTree(get("/v3/api-docs", null).body());
    }

    private HttpResponse<String> get(String path, String apiKey) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (apiKey != null) {
            req.header("X-API-Key", apiKey);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }
}
