package cn.apple.domain.legal.litigation;

public enum ProceedingType {
    CIVIL,
    ADMINISTRATIVE,
    LABOR_ARBITRATION,
    COMMERCIAL_ARBITRATION,
    CRIMINAL_DEFENSE;

    public static ProceedingType parse(Object value) {
        if (value == null) return null;
        String checked = value.toString().trim().toUpperCase();
        return switch (checked) {
            case "民事", "民事诉讼", "CIVIL" -> CIVIL;
            case "行政", "行政诉讼", "ADMINISTRATIVE" -> ADMINISTRATIVE;
            case "劳动仲裁", "LABOR_ARBITRATION" -> LABOR_ARBITRATION;
            case "商事仲裁", "COMMERCIAL_ARBITRATION" -> COMMERCIAL_ARBITRATION;
            case "刑事辩护", "CRIMINAL_DEFENSE" -> CRIMINAL_DEFENSE;
            default -> null;
        };
    }
}
