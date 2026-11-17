package cn.apple.test.domain;

import cn.apple.api.LegalMatterController;
import cn.apple.application.legal.matter.InMemoryLegalMatterRepository;
import cn.apple.application.legal.matter.LegalMatterApplicationService;
import cn.apple.domain.legal.matter.MatterScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class MatterScopeIntegrationTest {
    @Test public void scopeSurvivesCreateAndTenantScopedRead() {
        var service = new LegalMatterApplicationService(new InMemoryLegalMatterRepository());
        var scope = new MatterScope("contract", List.of("cn", "US-NY", "cn"), List.of("zh-CN", "en"));
        var created = service.create("tenant-a", "actor-a", "Cross-border contract", "C-1", Map.of(), scope);
        assertEquals(List.of("CN", "US-NY"), created.scope().relatedJurisdictions());
        assertEquals(scope, service.get("tenant-a", created.matter().id()).scope());
        assertTrue(service.list("tenant-b").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.get("tenant-b", created.matter().id()));
    }

    @Test public void legacyRequestsRemainUnspecified() throws Exception {
        var request = new ObjectMapper().readValue("{\"title\":\"Legacy\",\"clientReference\":\"C-1\",\"attributes\":{}}",
                LegalMatterController.CreateMatterRequest.class);
        var service = new LegalMatterApplicationService(new InMemoryLegalMatterRepository());
        var created = service.create("tenant-a", "actor-a", request.title(), request.clientReference(), request.attributes(), request.scope());
        assertEquals(MatterScope.unspecified(), created.scope());
    }

    @Test public void jsonContractRoundTripsStructuredScope() throws Exception {
        var mapper = new ObjectMapper();
        var request = mapper.readValue("{\"title\":\"Cross-border\",\"scope\":{\"businessType\":\"contract\",\"relatedJurisdictions\":[\"cn\",\"US-NY\"],\"workingLanguages\":[\"en\"]}}",
                LegalMatterController.CreateMatterRequest.class);
        assertEquals("CONTRACT", request.scope().businessType());
        assertEquals(request.scope(), mapper.readValue(mapper.writeValueAsString(request.scope()), MatterScope.class));
    }

    @Test public void attributesCannotOverrideScopeOrAuthenticatedOwner() {
        var service = new LegalMatterApplicationService(new InMemoryLegalMatterRepository());
        assertThrows(IllegalArgumentException.class, () -> service.create("t", "actor", "Title", "C-1",
                Map.of("matterScope.businessType", "CONTRACT")));
        var created = service.create("t", "actor", "Title", "C-1", Map.of("ownerActorId", "someone-else"));
        assertEquals("actor", created.matter().attributes().get("ownerActorId"));
    }

    @Test public void invalidOrUnboundedScopeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MatterScope("contract", List.of("CN,US"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MatterScope("contract", List.of(""), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MatterScope("contract", java.util.Collections.nCopies(21, "CN"), List.of()));
        assertThrows(UnsupportedOperationException.class, () -> new MatterScope("contract", List.of("CN"), List.of()).relatedJurisdictions().add("US"));
    }
    @Test public void controllerUsesAuthenticatedContextAndReturnsScope() {
        var service = new LegalMatterApplicationService(new InMemoryLegalMatterRepository());
        var controller = new LegalMatterController(service, true);
        var request = new LegalMatterController.CreateMatterRequest("Matter", "C-2", Map.of(),
                new MatterScope("contract", List.of("CN", "US-NY"), List.of("en")));
        cn.apple.config.TenantContext.clear();
        assertFalse(controller.create("spoofed", "spoofed", request).success());
        try {
            cn.apple.config.TenantContext.set(new cn.apple.config.TenantContext.Principal(
                    "real-tenant", "real-actor", cn.apple.domain.legal.matter.MatterActorRole.LAWYER));
            var result = controller.create("spoofed", "spoofed", request);
            assertTrue(result.success());
            var created = (LegalMatterApplicationService.MatterView) result.data();
            assertEquals("real-tenant", created.matter().tenantId());
            assertEquals("real-actor", created.matter().attributes().get("ownerActorId"));
            var fetched = (LegalMatterApplicationService.MatterView) controller.get("spoofed", created.matter().id()).data();
            assertEquals(request.scope(), fetched.scope());
        } finally {
            cn.apple.config.TenantContext.clear();
        }
    }

    @Test public void normalizedAttributesCannotBypassReservedFields() {
        var service = new LegalMatterApplicationService(new InMemoryLegalMatterRepository());
        assertThrows(IllegalArgumentException.class, () -> service.create("t", "actor", "Title", "C-1",
                Map.of(" matterScope.businessType ", "CONTRACT")));
        var created = service.create("t", "actor", "Title", "C-1", Map.of(" ownerActorId ", "attacker"));
        assertEquals("actor", created.matter().attributes().get("ownerActorId"));
        assertThrows(IllegalArgumentException.class, () -> new MatterScope("contract", List.of(), List.of("zh_CN")));
        assertThrows(IllegalArgumentException.class, () -> new MatterScope("contract", List.of(), List.of("e")));
    }

    @Test public void apiAggregatesSerializeIdentityArtifactsAndReviewBinding() throws Exception {
        var service = new LegalMatterApplicationService(new InMemoryLegalMatterRepository());
        var view = service.create("t", "actor", "Matter", "C");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var json = mapper.readTree(mapper.writeValueAsString(view));
        assertEquals(view.matter().id(), json.path("matter").path("id").asText());
        assertEquals("INTAKE", json.path("matter").path("status").asText());
        assertTrue(json.path("matter").path("artifacts").isArray());
        var task = cn.apple.domain.legal.matter.LegalReviewTask.create("R", "t", view.matter().id(),
                cn.apple.domain.legal.matter.ReviewTaskType.QUALITY_REVIEW, java.time.Instant.now());
        task.bindArtifact(new cn.apple.domain.legal.matter.ReviewArtifactBinding(1, "a".repeat(64), "b".repeat(64)));
        var taskJson = mapper.readTree(mapper.writeValueAsString(task));
        assertEquals("R", taskJson.path("id").asText());
        assertEquals(1, taskJson.path("artifactBinding").path("artifactVersion").asLong());
    }

    @Test public void clientQueriesCannotReadAnotherOwnersMatterOrReviewQueue() throws Exception {
        var repository = new InMemoryLegalMatterRepository();
        var service = new LegalMatterApplicationService(repository);
        String own = service.create("t", "client-a", "Own matter", "A").matter().id();
        String other = service.create("t", "client-b", "Other matter", "B").matter().id();
        repository.saveReviewTask(cn.apple.domain.legal.matter.LegalReviewTask.create("review-internal", "t", own,
                cn.apple.domain.legal.matter.ReviewTaskType.QUALITY_REVIEW, java.time.Instant.now()));
        repository.appendAudit(new cn.apple.domain.legal.matter.MatterAuditEvent("audit-internal", "t", own,
                "REVIEW_ASSIGNED", "reviewer-secret", cn.apple.domain.legal.matter.MatterActorRole.LAWYER,
                "correlation-secret", "internal review note", Map.of("internal", "detail"), java.time.Instant.now()));
        var controller = new LegalMatterController(service, true);
        try {
            cn.apple.config.TenantContext.set(new cn.apple.config.TenantContext.Principal(
                    "t", "client-a", cn.apple.domain.legal.matter.MatterActorRole.CLIENT));
            var mapper = new ObjectMapper().findAndRegisterModules();
            var clientContext = controller.context();
            assertTrue(clientContext.success());
            var contextJson = mapper.readTree(mapper.writeValueAsString(clientContext.data()));
            assertFalse(contextJson.has("tenantId"));
            assertFalse(contextJson.has("actorId"));
            assertTrue(controller.list("spoofed").success());
            assertTrue(controller.get(null, own).success());
            assertFalse(controller.timeline(null, own).success());
            assertEquals(1, service.list("t", "client-a", cn.apple.domain.legal.matter.MatterActorRole.CLIENT).size());
            assertEquals(own, service.get("t", own, "client-a", cn.apple.domain.legal.matter.MatterActorRole.CLIENT).matter().id());
            var clientResponse = controller.get(null, own);
            assertTrue(clientResponse.message(), clientResponse.success());
            var clientView = clientResponse.data();
            assertTrue(clientView instanceof LegalMatterController.ClientMatterView);
            var clientJson = mapper.readTree(mapper.writeValueAsString(clientView));
            assertFalse(clientJson.toString().contains("review-internal"));
            assertFalse(clientJson.toString().contains("audit-internal"));
            assertFalse(clientJson.toString().contains("reviewer-secret"));
            assertFalse(clientJson.toString().contains("internal review note"));
            assertFalse(clientJson.path("matter").has("tenantId"));
            assertFalse(clientJson.path("matter").has("attributes"));
            assertFalse(clientJson.path("matter").has("transitions"));
            var listJson = mapper.readTree(mapper.writeValueAsString(controller.list(null).data()));
            assertFalse(listJson.toString().contains("audit-internal"));
            var createdByClient = controller.create(null, null, new LegalMatterController.CreateMatterRequest("Client request", "C-3", Map.of()));
            assertTrue(createdByClient.success());
            assertTrue(createdByClient.data() instanceof LegalMatterController.ClientMatterView);
            assertThrows(SecurityException.class, () -> service.get("t", other, "client-a", cn.apple.domain.legal.matter.MatterActorRole.CLIENT));
            assertThrows(SecurityException.class, () -> service.timeline("t", other, "client-a", cn.apple.domain.legal.matter.MatterActorRole.CLIENT));
            assertFalse(controller.get(null, other).success());
            assertFalse(controller.timeline(null, other).success());
            assertFalse(controller.reviewQueue(null).success());
            cn.apple.config.TenantContext.set(new cn.apple.config.TenantContext.Principal(
                    "t", "lawyer", cn.apple.domain.legal.matter.MatterActorRole.LAWYER));
            assertEquals(3, ((List<?>) controller.list(null).data()).size());
            assertTrue(controller.get(null, other).success());
            assertTrue(controller.reviewQueue(null).success());
        } finally { cn.apple.config.TenantContext.clear(); }
    }

}
