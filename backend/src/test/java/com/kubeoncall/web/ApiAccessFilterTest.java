package com.kubeoncall.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.kubeoncall.common.config.KubeOnCallProperties;

class ApiAccessFilterTest {

    @Test
    void shouldRequireAdminForKnowledgeMutation() throws Exception {
        Fixture fixture = new Fixture();

        assertEquals(403, fixture.invoke("POST", "/api/knowledge/index/activate/v2", "viewer-token"));
        assertEquals(200, fixture.invoke("POST", "/api/knowledge/index/activate/v2", "admin-token"));
    }

    @Test
    void shouldAllowViewerQueryAndOperatorApproval() throws Exception {
        Fixture fixture = new Fixture();

        assertEquals(200, fixture.invoke("POST", "/api/knowledge/query", "viewer-token"));
        assertEquals(403, fixture.invoke("POST", "/api/approvals/exec-1", "viewer-token"));
        assertEquals(200, fixture.invoke("POST", "/api/approvals/exec-1", "operator-token"));
    }

    @Test
    void shouldLeaveSignedIntegrationWebhooksOnTheirOwnAuthenticationPath() throws Exception {
        Fixture fixture = new Fixture();

        assertEquals(200, fixture.invoke("POST", "/api/integrations/alertmanager/webhook", null));
        assertEquals(200, fixture.invoke("POST", "/api/integrations/change-events/github", null));
    }

    @Test
    void shouldLeaveV1LoginAndAuthenticatedRoutesToV1SecurityChain() throws Exception {
        Fixture fixture = new Fixture();

        assertEquals(200, fixture.invoke("POST", "/api/v1/auth/login", null));
        assertEquals(200, fixture.invoke("GET", "/api/v1/alarms", null));
    }

    private static class Fixture {
        private final ApiAccessFilter filter;

        private Fixture() {
            KubeOnCallProperties properties = new KubeOnCallProperties();
            properties.getApiSecurity().setEnabled(true);
            properties.getApiSecurity().setViewerToken("viewer-token");
            properties.getApiSecurity().setOperatorToken("operator-token");
            properties.getApiSecurity().setAdminToken("admin-token");
            filter = new ApiAccessFilter(properties);
        }

        private int invoke(String method, String path, String token) throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest(method, path);
            if (token != null) {
                request.addHeader("Authorization", "Bearer " + token);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            return response.getStatus();
        }
    }
}
