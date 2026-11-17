package cn.apple.domain.legal.litigation;

public enum PartyRole {
    PLAINTIFF,
    APPLICANT,
    DEFENDANT,
    RESPONDENT,
    THIRD_PARTY;

    public static PartyRole parse(Object value) {
        if (value == null) return null;
        String checked = value.toString().trim().toUpperCase();
        return switch (checked) {
            case "原告", "PLAINTIFF" -> PLAINTIFF;
            case "申请人", "APPLICANT" -> APPLICANT;
            case "被告", "DEFENDANT" -> DEFENDANT;
            case "被申请人", "RESPONDENT" -> RESPONDENT;
            case "第三人", "THIRD_PARTY" -> THIRD_PARTY;
            default -> null;
        };
    }
}
