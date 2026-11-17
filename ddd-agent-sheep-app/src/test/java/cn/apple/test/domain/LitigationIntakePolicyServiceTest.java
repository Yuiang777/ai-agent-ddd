package cn.apple.test.domain;

import cn.apple.domain.legal.litigation.LitigationIntakeDecision;
import cn.apple.domain.legal.litigation.LitigationIntakePolicyService;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LitigationIntakePolicyServiceTest {
    private final LitigationIntakePolicyService policy = new LitigationIntakePolicyService();

    @Test
    public void shouldApprovePlaintiffWithCompletedIntake() {
        LitigationIntakeDecision decision = policy.evaluate("tenant-a", "准备起诉并依法维权", complete("PLAINTIFF"));
        assertTrue(decision.litigation());
        assertTrue(decision.approved());
        assertTrue((Boolean) decision.normalized().get("lawyerSignoffRequired"));
    }

    @Test
    public void shouldApproveDefendantWithCompletedIntake() {
        LitigationIntakeDecision decision = policy.evaluate("tenant-a", "准备答辩", complete("DEFENDANT"));
        assertTrue(decision.approved());
        assertTrue("DEFENDANT".equals(decision.normalized().get("partyRole")));
    }

    @Test
    public void shouldBlockWhenConflictCheckIsMissing() {
        Map<String, Object> values = complete("PLAINTIFF");
        values = new java.util.LinkedHashMap<>(values);
        values.put("conflictCheckCompleted", false);
        LitigationIntakeDecision decision = policy.evaluate("tenant-a", "我要起诉", values);
        assertFalse(decision.approved());
        assertTrue(decision.reasons().stream().anyMatch(reason -> reason.contains("利益冲突")));
    }

    @Test
    public void shouldBlockWhenEngagementAuthorizationIsMissing() {
        Map<String, Object> values = complete("DEFENDANT");
        values = new java.util.LinkedHashMap<>(values);
        values.put("engagementAuthorized", false);
        LitigationIntakeDecision decision = policy.evaluate("tenant-a", "准备应诉", values);
        assertFalse(decision.approved());
        assertTrue(decision.reasons().stream().anyMatch(reason -> reason.contains("委托授权")));
    }

    @Test
    public void shouldFailClosedForFabricatedEvidenceRequest() {
        LitigationIntakeDecision decision = policy.evaluate("tenant-a", "请帮我伪造证据以便起诉", complete("PLAINTIFF"));
        assertFalse(decision.approved());
        assertTrue(decision.prohibitedConduct());
    }

    @Test
    public void shouldAllowDefensiveResponseToOpponentFabrication() {
        LitigationIntakeDecision decision = policy.evaluate(
                "law-consultant", "对方伪造证据，我方如何质证并申请鉴定", complete("DEFENDANT"));
        assertTrue(decision.approved());
        assertFalse(decision.prohibitedConduct());
    }

    @Test
    public void shouldNotLetProtectiveLanguageMaskAnotherProhibitedRequest() {
        LitigationIntakeDecision decision = policy.evaluate(
                "law-consultant", "对方伪造证据如何应对，同时帮我销毁证据", complete("DEFENDANT"));
        assertFalse(decision.approved());
        assertTrue(decision.prohibitedConduct());
    }

    @Test
    public void shouldRequireConflictCheckReference() {
        Map<String, Object> values = new java.util.LinkedHashMap<>(complete("PLAINTIFF"));
        values.remove("conflictCheckReference");
        LitigationIntakeDecision decision = policy.evaluate("law-consultant", "我要起诉", values);
        assertFalse(decision.approved());
        assertTrue(decision.reasons().stream().anyMatch(reason -> reason.contains("审查编号")));
    }

    @Test
    public void shouldRequireEngagementReference() {
        Map<String, Object> values = new java.util.LinkedHashMap<>(complete("PLAINTIFF"));
        values.remove("engagementReference");
        LitigationIntakeDecision decision = policy.evaluate("law-consultant", "我要起诉", values);
        assertFalse(decision.approved());
        assertTrue(decision.reasons().stream().anyMatch(reason -> reason.contains("授权书")));
    }

    @Test
    public void shouldRejectMalformedDeadline() {
        Map<String, Object> values = new java.util.LinkedHashMap<>(complete("PLAINTIFF"));
        values.put("nextDeadline", "2026/08/20");
        values.put("deadlineSource", "法院通知");
        LitigationIntakeDecision decision = policy.evaluate("law-consultant", "我要起诉", values);
        assertFalse(decision.approved());
        assertTrue(decision.reasons().stream().anyMatch(reason -> reason.contains("yyyy-MM-dd")));
    }

    private Map<String, Object> complete(String role) {
        return Map.ofEntries(
                Map.entry("matterReference", "MAT-2026-001"),
                Map.entry("representedPartyName", "甲公司"),
                Map.entry("partyRole", role),
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
