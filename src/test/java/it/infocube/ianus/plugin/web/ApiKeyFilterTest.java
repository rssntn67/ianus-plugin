package it.infocube.ianus.plugin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.infocube.ianus.plugin.config.IanusProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiKeyFilterTest {

    private final ApiKeyFilter filter = new ApiKeyFilter(new IanusProperties("secret", null, null, null, null, null, null));

    @Test
    void acceptsValidKey() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ianus/v2/health");
        req.addHeader(ApiKeyFilter.HEADER, "secret");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsMissingOrWrongKey() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/ianus/v2/opennms/metrics");
        req.addHeader(ApiKeyFilter.HEADER, "wrong");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        assertThat(res.getContentAsString()).contains("unauthorized");
    }

    @Test
    void openApiDocsAreNotFiltered() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v3/api-docs");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void refusesToStartWithoutKey() {
        assertThatThrownBy(() -> new ApiKeyFilter(new IanusProperties(" ", null, null, null, null, null, null)))
                .isInstanceOf(IllegalStateException.class);
    }
}
