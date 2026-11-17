package cn.apple.domain.legal.litigation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Deterministic intake gate. It runs before OCR, retrieval or substantive model reasoning. */
public class LitigationIntakePolicyService {
    private static final List<String> PROHIBITED_TERMS = List.of(
            "伪造证据", "制造证据", "隐匿证据", "销毁证据", "毁灭证据", "虚假陈述", "作伪证",
            "收买证人", "威胁证人", "贿赂", "打点法官", "不当接触法官", "转移资产规避执行"
    );
    private static final List<String> LITIGATION_SIGNALS = List.of(
            "起诉", "应诉", "仲裁", "被告", "原告", "答辩", "答辩状", "诉状", "立案", "开庭",
            "上诉", "再审", "执行", "强制执行", "保全", "财产保全", "证据保全", "传票", "裁判文书"
    );

    public LitigationIntakeDecision evaluate(String clientId, String message, Map<String, Object> variables) {
        Map<String, Object> input = variables == null ? Map.of() : variables;
        PartyRole role = PartyRole.parse(input.get("partyRole"));
        ProceedingType type = ProceedingType.parse(input.get("proceedingType"));
        ProceedingStage stage = ProceedingStage.parse(input.get("proceedingStage"));
        boolean litigation = type != null || role != null || containsLitigationSignal(message);
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("casePosture", litigation ? "LITIGATION" : "ADVISORY");
        if (role != null) normalized.put("partyRole", role.name());
        if (type != null) normalized.put("proceedingType", type.name());
        if (stage != null) normalized.put("proceedingStage", stage.name());

        List<String> reasons = new ArrayList<>();
        boolean prohibited = containsProhibited(message, input);
        if (prohibited) reasons.add("请求涉及伪造、隐匿、毁灭证据或不当影响司法等禁止行为");
        if (!litigation) {
            normalized.put("intakeApproved", !prohibited);
            normalized.put("lawyerSignoffRequired", false);
            return new LitigationIntakeDecision(false, !prohibited, prohibited, List.copyOf(reasons), Map.copyOf(normalized));
        }
        if (isBlank(input.get("matterReference"))) reasons.add("缺少可追溯的案件或法律事项编号");
        if (isBlank(input.get("representedPartyName"))) reasons.add("必须登记实际委托方或被代理当事人名称");
        if (role == null) reasons.add("必须明确委托方诉讼地位（原告/申请人或被告/被申请人等）");
        if (isBlank(input.get("opponentNames"))) reasons.add("必须登记对方当事人或相对方名称");
        if (!bool(input.get("conflictCheckCompleted"))) reasons.add("利益冲突审查尚未完成");
        if (bool(input.get("conflictCheckCompleted")) && isBlank(input.get("conflictCheckReference"))) {
            reasons.add("利益冲突审查缺少可追溯的审查编号或记录引用");
        }
        if (!bool(input.get("engagementAuthorized"))) reasons.add("委托授权或代理权限尚未确认");
        if (bool(input.get("engagementAuthorized")) && isBlank(input.get("engagementReference"))) {
            reasons.add("委托授权缺少授权书、委托合同或审批记录引用");
        }
        if (isBlank(input.get("courtOrTribunal"))) reasons.add("管辖法院、仲裁机构或主管机关尚未确认");
        if (type == null) reasons.add("案件程序类型尚未确认");
        if (stage == null) reasons.add("当前程序阶段尚未确认");
        if (!isBlank(input.get("nextDeadline")) && isBlank(input.get("deadlineSource"))) {
            reasons.add("最近程序期限缺少法定、法院指定或仲裁机构通知等依据");
        }
        if (!isBlank(input.get("nextDeadline")) && !isIsoDate(input.get("nextDeadline"))) {
            reasons.add("最近程序期限格式必须为 yyyy-MM-dd");
        }
        normalized.put("intakeApproved", reasons.isEmpty());
        normalized.put("lawyerSignoffRequired", true);
        normalized.put("representedPartyName", text(input.get("representedPartyName")));
        normalized.put("opponentNames", text(input.get("opponentNames")));
        normalized.put("conflictCheckReference", text(input.get("conflictCheckReference")));
        normalized.put("engagementReference", text(input.get("engagementReference")));
        normalized.put("deadlineSource", text(input.get("deadlineSource")));
        normalized.put("matterReference", text(input.get("matterReference")));
        return new LitigationIntakeDecision(true, reasons.isEmpty(), prohibited, List.copyOf(reasons), Map.copyOf(normalized));
    }

    private boolean containsLitigationSignal(String message) {
        String value = message == null ? "" : message;
        return LITIGATION_SIGNALS.stream().anyMatch(value::contains);
    }

    private boolean containsProhibited(String message, Map<String, Object> variables) {
        StringBuilder text = new StringBuilder(message == null ? "" : message);
        variables.forEach((key, value) -> text.append(' ').append(key).append(' ').append(value));
        String lower = text.toString().toLowerCase(Locale.ROOT);
        return PROHIBITED_TERMS.stream().anyMatch(term -> hasUnprotectedOccurrence(lower, term));
    }

    private boolean hasUnprotectedOccurrence(String text, String term) {
        int from = 0;
        while (from < text.length()) {
            int index = text.indexOf(term, from);
            if (index < 0) return false;
            if (!isProtectiveEvidenceContext(text, term, index)) return true;
            from = index + term.length();
        }
        return false;
    }

    private boolean isProtectiveEvidenceContext(String text, String term, int index) {
        if (!(term.contains("证据") || term.contains("伪证") || term.contains("虚假"))) return false;
        int beforeStart = Math.max(0, index - 14);
        int afterEnd = Math.min(text.length(), index + term.length() + 32);
        String before = text.substring(beforeStart, index);
        String nearby = text.substring(beforeStart, afterEnd);
        boolean attributedToOtherParty = List.of("对方", "他方", "他人", "原告", "被告", "申请人", "被申请人", "证人")
                .stream().anyMatch(before::contains);
        boolean protectiveAction = List.of("如何应对", "如何举证", "核验", "举报", "申请调查", "申请鉴定", "质证", "排除", "证据保全", "追究")
                .stream().anyMatch(nearby::contains);
        return attributedToOtherParty && protectiveAction;
    }

    private boolean isIsoDate(Object value) {
        try {
            LocalDate.parse(value.toString().trim());
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private boolean bool(Object value) { return value instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(value)); }
    private boolean isBlank(Object value) { return value == null || value.toString().trim().isEmpty(); }
    private String text(Object value) { return value == null ? "" : value.toString().trim(); }
}
