package cn.apple.domain.legal.model.valobj;

import java.util.Locale;

public enum LegalCorpusType {
    PUBLIC_LAW("public_law", false, false),
    PUBLIC_CASE("public_case", false, false),
    FIRM_KNOWLEDGE("firm_knowledge", true, false),
    MATTER_PRIVATE("matter_private", true, true);

    private final String code;
    private final boolean tenantRequired;
    private final boolean matterRequired;

    LegalCorpusType(String code, boolean tenantRequired, boolean matterRequired) {
        this.code = code;
        this.tenantRequired = tenantRequired;
        this.matterRequired = matterRequired;
    }

    public String code() {
        return code;
    }

    public boolean tenantRequired() {
        return tenantRequired;
    }

    public boolean matterRequired() {
        return matterRequired;
    }

    public static LegalCorpusType fromCode(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (LegalCorpusType type : values()) {
            if (type.code.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unsupported legal corpus type: " + value);
    }
}
