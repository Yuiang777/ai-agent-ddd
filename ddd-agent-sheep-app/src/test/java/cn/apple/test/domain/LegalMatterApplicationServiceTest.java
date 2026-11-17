package cn.apple.test.domain;

import cn.apple.application.legal.LegalMatterApplicationService;
import cn.apple.domain.legal.matter.MatterActorRole;
import cn.apple.domain.legal.matter.MatterStatus;
import cn.apple.domain.legal.matter.MatterTransitionCommand;
import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LegalMatterApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Test
    public void shouldKeepMatterLookupTenantScoped() {
        LegalMatterApplicationService service = new LegalMatterApplicationService();
        var matter = service.createMatter("tenant-a", "Advice", "client-1", NOW);
        assertTrue(service.findMatter("tenant-a", matter.id()).isPresent());
        assertTrue(service.findMatter("tenant-b", matter.id()).isEmpty());
    }

    @Test
    public void shouldApplyTransitionsThroughDomainAggregate() {
        LegalMatterApplicationService service = new LegalMatterApplicationService();
        var matter = service.createMatter("tenant-a", "Advice", "client-1", NOW);
        service.transitionMatter("tenant-a", matter.id(), MatterStatus.MATERIAL_PENDING,
                MatterTransitionCommand.system("collect materials", NOW));
        service.transitionMatter("tenant-a", matter.id(), MatterStatus.ANALYSIS,
                new MatterTransitionCommand("lawyer-1", MatterActorRole.LAWYER, "ready", "", 1L, NOW));
        assertEquals(MatterStatus.ANALYSIS, service.findMatter("tenant-a", matter.id()).orElseThrow().status());
    }
}
