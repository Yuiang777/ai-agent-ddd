package cn.apple.application.agent;

public record ModelInvocationPolicy(String operation,
                                    boolean toolCall,
                                    int maxAttempts) {

    public static ModelInvocationPolicy readOnly(String operation) {
        return new ModelInvocationPolicy(operation, false, 2);
    }

    public static ModelInvocationPolicy toolCall(String operation) {
        return new ModelInvocationPolicy(operation, true, 1);
    }

    public int normalizedAttempts() {
        return toolCall ? 1 : Math.max(1, Math.min(2, maxAttempts));
    }
}
