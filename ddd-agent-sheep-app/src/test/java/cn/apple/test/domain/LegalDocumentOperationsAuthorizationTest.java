package cn.apple.test.domain;

import cn.apple.api.LegalDocumentController;
import cn.apple.config.TenantContext;
import cn.apple.domain.legal.matter.MatterActorRole;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegalDocumentOperationsAuthorizationTest {
    private LegalDocumentController controller() {
        return new LegalDocumentController(null, null, null, null, null, true, true);
    }

    @Test
    public void ingestionAndGlobalStatsRequireAuthenticatedStaffBeforeServiceAccess() {
        TenantContext.clear();
        for (int operation = 0; operation < 2; operation++) {
            try {
                if (operation == 0) controller().ingest(); else controller().stats();
                fail("Missing identity must be rejected");
            } catch (SecurityException error) {
                assertEquals("AUTHENTICATED_ACTOR_CONTEXT_REQUIRED", error.getMessage());
            }
        }
        TenantContext.set(new TenantContext.Principal("tenant", "client", MatterActorRole.CLIENT));
        try {
            for (int operation = 0; operation < 2; operation++) {
                try {
                    if (operation == 0) controller().ingest(); else controller().stats();
                    fail("External client role must be rejected");
                } catch (SecurityException error) {
                    assertEquals("INTERNAL_LEGAL_STAFF_REQUIRED", error.getMessage());
                }
            }
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    public void authenticatedStaffPassesAuthorizationBeforeServiceInvocation() {
        TenantContext.set(new TenantContext.Principal("tenant", "lawyer", MatterActorRole.LAWYER));
        try {
            for (int operation = 0; operation < 2; operation++) {
                try {
                    if (operation == 0) controller().ingest(); else controller().stats();
                    fail("Authorized request proceeds to configured service");
                } catch (NullPointerException expectedFromNullTestService) {
                    assertNotEquals("Authorization must not fail", SecurityException.class,
                            expectedFromNullTestService.getClass());
                }
            }
        } finally {
            TenantContext.clear();
        }
    }
}
