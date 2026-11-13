package cn.apple.test.domain;

import cn.apple.application.workflow.LitigationUploadAuthorizationService;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LitigationUploadAuthorizationServiceTest {

    @Test
    public void shouldConsumeMatchingGrantOnlyOnce() {
        LitigationUploadAuthorizationService service = new LitigationUploadAuthorizationService();
        Map<String, Object> variables = completeIntake();
        LitigationUploadAuthorizationService.UploadAuthorization authorization =
                service.issue("law-consultant", "准备起诉", variables);

        assertNotNull(authorization.token());
        assertTrue(service.isActive(authorization.token()));
        service.consume(authorization.token(), "law-consultant", "准备起诉", variables);
        assertFalse(service.isActive(authorization.token()));
        assertThrows(IllegalArgumentException.class,
                () -> service.consume(authorization.token(), "law-consultant", "准备起诉", variables));
    }

    @Test
    public void shouldRejectMetadataMismatchAndInvalidateGrant() {
        LitigationUploadAuthorizationService service = new LitigationUploadAuthorizationService();
        Map<String, Object> variables = completeIntake();
        String token = service.issue("law-consultant", "准备答辩", variables).token();

        assertThrows(IllegalArgumentException.class,
                () -> service.consume(token, "law-consultant", "不同请求", variables));
        assertFalse(service.isActive(token));
    }

    @Test
    public void shouldRefuseUploadWhenIntakeIsBlocked() {
        LitigationUploadAuthorizationService service = new LitigationUploadAuthorizationService();
        Map<String, Object> variables = new LinkedHashMap<>(completeIntake());
        variables.put("conflictCheckCompleted", false);

        assertThrows(IllegalArgumentException.class,
                () -> service.issue("law-consultant", "准备起诉", variables));
    }

    private Map<String, Object> completeIntake() {
        return Map.ofEntries(
                Map.entry("matterReference", "MAT-2026-001"),
                Map.entry("representedPartyName", "甲公司"),
                Map.entry("partyRole", "PLAINTIFF"),
                Map.entry("opponentNames", "乙公司"),
                Map.entry("conflictCheckCompleted", true),
                Map.entry("conflictCheckReference", "COI-2026-001"),
                Map.entry("engagementAuthorized", true),
                Map.entry("engagementReference", "ENG-2026-001"),
                Map.entry("courtOrTribunal", "北京市某区人民法院"),
                Map.entry("proceedingType", "CIVIL"),
                Map.entry("proceedingStage", "PRE_ACTION")
        );
    }
}
