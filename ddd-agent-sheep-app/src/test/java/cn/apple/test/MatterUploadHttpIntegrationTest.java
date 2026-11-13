package cn.apple.test;

import cn.apple.Application;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.Assert.*;

/** Real HTTP + multipart + filesystem contract; no external infrastructure or model calls. */
public class MatterUploadHttpIntegrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void rejectsOfflineUploadBeforeWritingFilesAndRejectsForeignMatter() throws Exception {
        Path root = temporary.newFolder("runtime").toPath();
        try (var context = (ServletWebServerApplicationContext) new SpringApplicationBuilder(Application.class)
                .profiles("offline").web(WebApplicationType.SERVLET).run(
                    "--server.port=0", "--server.address=127.0.0.1",
                    "--app.infrastructure.enabled=false", "--app.model-runtime.enabled=false",
                    "--app.admin-api.enabled=true", "--app.admin-api.token=test-local-token",
                    "--app.enterprise.security-context-enabled=true", "--app.enterprise.trusted-context-header-enabled=true",
                    "--app.legal.knowledge.storage-root=" + root.resolve("knowledge"),
                    "--app.legal.object-storage.filesystem-root=" + root.resolve("objects"),
                    "--workflow.storage-root=" + root.resolve("workflows"))) {
            RestTemplate http = new RestTemplate();
            String base = "http://127.0.0.1:" + context.getWebServer().getPort() + "/api";
            HttpHeaders headers = headers("tenant-a");
            JsonNode capabilities = http.exchange(base + "/legal/documents/upload-capabilities", HttpMethod.GET,
                    new HttpEntity<>(headers), JsonNode.class).getBody();
            assertTrue(capabilities.toString(), capabilities.path("success").asBoolean());
            assertFalse(capabilities.path("data").path("enabled").asBoolean());
            assertEquals("DISABLED", capabilities.path("data").path("status").asText());
            // Spring's offline multipart default is 1MB, even though the domain permits 200MB.
            assertEquals(1024 * 1024, capabilities.path("data").path("maxFileBytes").asLong());
            assertEquals(5, capabilities.path("data").path("allowedExtensions").size());
            JsonNode created = http.postForObject(base + "/legal/matters", new HttpEntity<>(
                    Map.of("title", "Upload HTTP test", "clientReference", "client-test"), headers), JsonNode.class);
            assertTrue(created.toString(), created.path("success").asBoolean());
            String id = created.path("data").path("matter").path("id").asText();
            byte[] bytes = "Evidence original text".getBytes(StandardCharsets.UTF_8);
            var form = new LinkedMultiValueMap<String, Object>();
            HttpHeaders fileHeaders = new HttpHeaders(); fileHeaders.setContentType(MediaType.TEXT_PLAIN);
            form.add("file", new HttpEntity<>(new ByteArrayResource(bytes) {
                @Override public String getFilename() { return "evidence.txt"; }
            }, fileHeaders));
            form.add("corpusType", "MATTER_PRIVATE"); form.add("matterId", id);
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            JsonNode uploaded = http.postForObject(base + "/legal/documents/upload", new HttpEntity<>(form, headers), JsonNode.class);
            assertFalse(uploaded.toString(), uploaded.path("success").asBoolean());
            assertEquals("LEGAL_DOCUMENT_CATALOG_DISABLED", uploaded.path("message").asText());
            assertEquals(0, objectCount(root));
            HttpHeaders foreign = headers("tenant-b"); foreign.setContentType(MediaType.MULTIPART_FORM_DATA);
            JsonNode denied = http.postForObject(base + "/legal/documents/upload", new HttpEntity<>(form, foreign), JsonNode.class);
            assertFalse(denied.toString(), denied.path("success").asBoolean());
            assertEquals("MATTER_NOT_FOUND", denied.path("message").asText());
            assertEquals(0, objectCount(root));

            // Existing-object metadata registration remains usable offline.
            headers.setContentType(MediaType.APPLICATION_JSON);
            JsonNode registered = http.postForObject(base + "/legal/matters/" + id + "/evidence", new HttpEntity<>(Map.of(
                    "id", "E-1", "version", 1, "objectReference", "external:verified-original",
                    "sha256", "a".repeat(64), "sourceType", "CLIENT_SUBMITTED", "custodian", "spoofed-user"), headers), JsonNode.class);
            assertTrue(registered.toString(), registered.path("success").asBoolean());
            assertEquals("test-lawyer", registered.path("data").path("evidence").get(0).path("custodian").asText());
            JsonNode preserved = http.postForObject(base + "/legal/matters/" + id + "/evidence/E-1/preserve",
                    new HttpEntity<>(Map.of(), headers), JsonNode.class);
            assertTrue(preserved.toString(), preserved.path("success").asBoolean());
            assertEquals("PRESERVED", preserved.path("data").path("evidence").get(1).path("preservationStatus").asText());
            JsonNode audit = http.exchange(base + "/legal/matters/" + id + "/timeline", HttpMethod.GET,
                    new HttpEntity<>(headers), JsonNode.class).getBody();
            assertEquals("LAWYER", audit.path("data").path("audit").get(0).path("actorRole").asText());
            int evidenceEvents = 0;
            for (JsonNode event : audit.path("data").path("audit")) {
                if (event.path("eventType").asText().startsWith("EVIDENCE_")) {
                    assertEquals("LAWYER", event.path("actorRole").asText());
                    evidenceEvents++;
                }
            }
            assertEquals(2, evidenceEvents);
            JsonNode deadline = http.postForObject(base + "/legal/matters/" + id + "/deadlines", new HttpEntity<>(Map.of(
                    "id", "D-1", "dueAt", "2026-12-01T09:00:00Z", "zoneId", "Asia/Shanghai",
                    "basis", "COURT_ORDER", "basisReference", "court-notice-1"), headers), JsonNode.class);
            assertTrue(deadline.toString(), deadline.path("success").asBoolean());
            HttpHeaders assistant = headers("tenant-a"); assistant.set("X-Authenticated-Actor-Role", "PARALEGAL");
            JsonNode forbidden = http.postForObject(base + "/legal/matters/" + id + "/deadlines/D-1/verify",
                    new HttpEntity<>(Map.of(), assistant), JsonNode.class);
            assertFalse(forbidden.toString(), forbidden.path("success").asBoolean());
            assertEquals("LAWYER_DEADLINE_VERIFICATION_REQUIRED", forbidden.path("message").asText());
            JsonNode verified = http.postForObject(base + "/legal/matters/" + id + "/deadlines/D-1/verify",
                    new HttpEntity<>(Map.of(), headers), JsonNode.class);
            assertTrue(verified.toString(), verified.path("success").asBoolean());
            assertEquals("VERIFIED", verified.path("data").path("deadlines").get(0).path("status").asText());
            assertEquals("test-lawyer", verified.path("data").path("deadlines").get(0).path("verifiedBy").asText());
            JsonNode missingTime = http.postForObject(base + "/legal/matters/" + id + "/deadlines", new HttpEntity<>(Map.of(
                    "id", "D-invalid", "zoneId", "Asia/Shanghai", "basis", "COURT_ORDER", "basisReference", "ref"), headers), JsonNode.class);
            assertFalse(missingTime.toString(), missingTime.path("success").asBoolean());
            assertEquals("DEADLINE_DUE_AT_REQUIRED", missingTime.path("message").asText());
            JsonNode contextInfo = http.exchange(base + "/legal/matters/context", HttpMethod.GET,
                    new HttpEntity<>(headers), JsonNode.class).getBody();
            assertEquals("LAWYER", contextInfo.path("data").path("role").asText());
            HttpHeaders client = headers("tenant-a");
            client.set("X-Authenticated-Actor-Role", "CLIENT");
            JsonNode clientList = http.exchange(base + "/legal/matters", HttpMethod.GET,
                    new HttpEntity<>(client), JsonNode.class).getBody();
            assertTrue(clientList.toString(), clientList.path("success").asBoolean());
            assertEquals(1, clientList.path("data").size());
            JsonNode clientView = clientList.path("data").get(0);
            assertEquals(id, clientView.path("matter").path("id").asText());
            assertFalse(clientView.has("evidence"));
            assertFalse(clientView.has("deadlines"));
            assertFalse(clientView.has("reviewTasks"));
            assertFalse(clientView.path("matter").has("tenantId"));
            assertFalse(clientView.path("matter").has("attributes"));
            client.set("X-Authenticated-Actor-Id", "another-client");
            JsonNode otherClientList = http.exchange(base + "/legal/matters", HttpMethod.GET,
                    new HttpEntity<>(client), JsonNode.class).getBody();
            assertTrue(otherClientList.toString(), otherClientList.path("success").asBoolean());
            assertEquals(0, otherClientList.path("data").size());
            for (String rejectedRole : new String[] {"CLIENT", "SYSTEM", "invalid-role", ""}) {
                HttpHeaders rejected = headers("tenant-a");
                rejected.set("X-Authenticated-Actor-Role", rejectedRole);
                JsonNode forbiddenList = http.exchange(base + "/legal/matters", HttpMethod.GET,
                        new HttpEntity<>(rejected), JsonNode.class).getBody();
                if ("CLIENT".equals(rejectedRole)) {
                    assertTrue(forbiddenList.toString(), forbiddenList.path("success").asBoolean());
                } else {
                    assertFalse(forbiddenList.toString(), forbiddenList.path("success").asBoolean());
                }
                JsonNode forbiddenEvidence = http.postForObject(base + "/legal/matters/" + id + "/evidence",
                        new HttpEntity<>(Map.of("id", "FORBIDDEN", "version", 1, "objectReference", "test://forbidden",
                                "sha256", "b".repeat(64), "sourceType", "CLIENT_SUBMITTED", "preservationStatus", "UNVERIFIED"), rejected), JsonNode.class);
                assertFalse(forbiddenEvidence.toString(), forbiddenEvidence.path("success").asBoolean());
                JsonNode forbiddenUpload = http.exchange(base + "/legal/documents/upload-capabilities", HttpMethod.GET,
                        new HttpEntity<>(rejected), JsonNode.class).getBody();
                assertFalse(forbiddenUpload.toString(), forbiddenUpload.path("success").asBoolean());
            }
            HttpHeaders unauthenticated = new HttpHeaders(); unauthenticated.set("X-Admin-Token", "test-local-token");
            JsonNode noIdentity = http.exchange(base + "/legal/matters/context", HttpMethod.GET,
                    new HttpEntity<>(unauthenticated), JsonNode.class).getBody();
            assertFalse(noIdentity.path("success").asBoolean());
            assertEquals("AUTHENTICATED_ACTOR_ROLE_REQUIRED", noIdentity.path("message").asText());
            JsonNode ownMultimodalTasks = http.exchange(base + "/multimodal/tasks", HttpMethod.GET,
                    new HttpEntity<>(headers), JsonNode.class).getBody();
            assertTrue(ownMultimodalTasks.toString(), ownMultimodalTasks.path("success").asBoolean());
            assertEquals(0, ownMultimodalTasks.path("data").size());
            for (HttpHeaders deniedIdentity : new HttpHeaders[]{unauthenticated, client}) {
                for (String endpoint : new String[]{"/multimodal/tasks", "/multimodal/tasks/unknown", "/multimodal/capabilities"}) {
                    JsonNode blocked = http.exchange(base + endpoint, HttpMethod.GET,
                            new HttpEntity<>(deniedIdentity), JsonNode.class).getBody();
                    assertFalse(blocked.toString(), blocked.path("success").asBoolean());
                    assertEquals("MULTIMODAL_ACCESS_DENIED", blocked.path("message").asText());
                }
                var assetDenied = assertThrows(org.springframework.web.client.HttpClientErrorException.class,
                        () -> http.exchange(base + "/multimodal/assets/unknown.txt", HttpMethod.GET,
                                new HttpEntity<>(deniedIdentity), String.class));
                assertEquals(403, assetDenied.getStatusCode().value());
            }

        }
    }
    private long objectCount(Path root) throws Exception {
        if (!Files.exists(root.resolve("objects"))) return 0;
        try (var files = Files.walk(root.resolve("objects"))) { return files.filter(Files::isRegularFile).count(); }
    }
    private HttpHeaders headers(String tenant) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Admin-Token", "test-local-token");
        headers.set("X-Authenticated-Tenant-Id", tenant);
        headers.set("X-Authenticated-Actor-Id", "test-lawyer");
        headers.set("X-Authenticated-Actor-Role", "LAWYER");
        return headers;
    }
}
