package cn.apple.domain.improvement.model;

import java.util.Objects;

public record HarnessVersion(String versionId, String modelId, String checklistVersion,
                             int evidenceLimit, int contextBudgetChars) {
    public HarnessVersion {
        versionId = required(versionId);
        modelId = required(modelId);
        checklistVersion = required(checklistVersion);
        if (evidenceLimit < 1 || evidenceLimit > 20 || contextBudgetChars < 400
                || contextBudgetChars > 20000) {
            throw new IllegalArgumentException("Harness configuration exceeds offline limits");
        }
    }

    static String required(String value) {
        Objects.requireNonNull(value, "Required evaluation value");
        if (value.isBlank() || value.length() > 128 || !value.equals(value.trim())) {
            throw new IllegalArgumentException("Invalid evaluation identifier");
        }
        return value;
    }
}
