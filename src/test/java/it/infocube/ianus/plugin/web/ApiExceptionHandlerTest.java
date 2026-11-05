package it.infocube.ianus.plugin.web;

import static org.assertj.core.api.Assertions.assertThat;

import it.infocube.ianus.plugin.core.model.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class ApiExceptionHandlerTest {

    @Test
    void unexpectedErrorIs500WithoutInternalDetails() {
        ResponseEntity<ErrorResponse> res = new ApiExceptionHandler()
                .unexpected(new IllegalStateException("password=segreta"));

        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody().code()).isEqualTo("internal_error");
        assertThat(res.getBody().message()).isNotBlank().doesNotContain("segreta");
    }
}
