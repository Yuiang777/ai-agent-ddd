package cn.apple.domain.legal.matter;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Intake references only; these do not determine applicable law or supported capabilities. */
public record MatterScope(String businessType, List<String> relatedJurisdictions, List<String> workingLanguages) {
    public static final String PREFIX = "matterScope.";

    public MatterScope {
        businessType = businessType == null ? "UNSPECIFIED" : normalize(businessType);
        relatedJurisdictions = normalizeList(relatedJurisdictions);
        workingLanguages = normalizeList(workingLanguages).stream().map(value -> {
            if (value.contains("_")) throw new IllegalArgumentException("INVALID_WORKING_LANGUAGE");
            try {
                return new Locale.Builder().setLanguageTag(value).build().toLanguageTag();
            } catch (java.util.IllformedLocaleException error) {
                throw new IllegalArgumentException("INVALID_WORKING_LANGUAGE", error);
            }
        }).distinct().toList();
    }

    public static MatterScope unspecified() {
        return new MatterScope(null, List.of(), List.of());
    }

    public Map<String, String> toAttributes() {
        return Map.of(PREFIX + "businessType", businessType,
                PREFIX + "relatedJurisdictions", String.join(",", relatedJurisdictions),
                PREFIX + "workingLanguages", String.join(",", workingLanguages));
    }

    public static MatterScope fromAttributes(Map<String, String> attributes) {
        return new MatterScope(attributes.get(PREFIX + "businessType"),
                split(attributes.get(PREFIX + "relatedJurisdictions")),
                split(attributes.get(PREFIX + "workingLanguages")));
    }

    private static List<String> split(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split(",", -1));
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null) return List.of();
        if (values.size() > 20) throw new IllegalArgumentException("MATTER_SCOPE_TOO_MANY_VALUES");
        return values.stream().map(MatterScope::normalize).distinct().toList();
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException("INVALID_MATTER_SCOPE_VALUE");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9]*(?:[-_][A-Z0-9]+)*")) {
            throw new IllegalArgumentException("INVALID_MATTER_SCOPE_REFERENCE");
        }
        return normalized;
    }
}
