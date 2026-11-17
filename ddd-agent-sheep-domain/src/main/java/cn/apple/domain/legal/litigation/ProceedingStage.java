package cn.apple.domain.legal.litigation;

public enum ProceedingStage {
    PRE_ACTION,
    FILING,
    FIRST_INSTANCE,
    SECOND_INSTANCE,
    RETRIAL,
    ENFORCEMENT;

    public static ProceedingStage parse(Object value) {
        if (value == null) return null;
        String checked = value.toString().trim().toUpperCase();
        return switch (checked) {
            case "诉前", "PRE_ACTION" -> PRE_ACTION;
            case "立案", "FILING" -> FILING;
            case "一审", "FIRST_INSTANCE" -> FIRST_INSTANCE;
            case "二审", "SECOND_INSTANCE" -> SECOND_INSTANCE;
            case "再审", "RETRIAL" -> RETRIAL;
            case "执行", "ENFORCEMENT" -> ENFORCEMENT;
            default -> null;
        };
    }
}
