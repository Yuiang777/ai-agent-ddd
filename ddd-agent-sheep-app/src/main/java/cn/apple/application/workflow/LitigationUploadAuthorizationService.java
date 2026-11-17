package cn.apple.application.workflow;

import cn.apple.domain.legal.litigation.LitigationIntakeDecision;
import cn.apple.domain.legal.litigation.LitigationIntakePolicyService;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived, one-time authorization for a litigation upload. The request filter validates it
 * before Spring parses multipart content; the controller consumes it only after request metadata matches.
 */
@Service
public class LitigationUploadAuthorizationService {
    public static final String HEADER = "X-Workflow-Upload-Authorization";
    private static final long TTL_SECONDS = 300;
    private static final int MAX_GRANTS = 10_000;
    private static final int MAX_CLIENT_ID_LENGTH = 128;
    private static final int MAX_MESSAGE_LENGTH = 20_000;

    private final LitigationIntakePolicyService intakePolicyService = new LitigationIntakePolicyService();
    private final ConcurrentHashMap<String, Grant> grants = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    public UploadAuthorization issue(String clientId, String message, Map<String, Object> variables) {
        String checkedClientId = required(clientId, "clientId", MAX_CLIENT_ID_LENGTH);
        String checkedMessage = required(message, "message", MAX_MESSAGE_LENGTH);
        Map<String, Object> snapshot = variables == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(variables));
        LitigationIntakeDecision decision = intakePolicyService.evaluate(checkedClientId, checkedMessage, snapshot);
        if (!decision.approved()) {
            throw new IllegalArgumentException("案件受理未通过，不能上传材料：" + String.join("；", decision.reasons()));
        }
        cleanupExpired();
        if (grants.size() >= MAX_GRANTS) {
            throw new IllegalStateException("案件材料上传授权暂不可用，请稍后重试");
        }
        String token = newToken();
        Instant expiresAt = Instant.now().plusSeconds(TTL_SECONDS);
        grants.put(token, new Grant(checkedClientId, checkedMessage, snapshot, expiresAt));
        return new UploadAuthorization(token, expiresAt.toString());
    }

    public boolean isActive(String token) {
        if (token == null || token.isBlank()) return false;
        Grant grant = grants.get(token.trim());
        return grant != null && grant.expiresAt().isAfter(Instant.now());
    }

    public void consume(String token, String clientId, String message, Map<String, Object> variables) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("缺少案件材料上传授权");
        Grant grant = grants.remove(token.trim());
        if (grant == null || !grant.expiresAt().isAfter(Instant.now())) {
            throw new IllegalArgumentException("案件材料上传授权无效或已过期，请重新完成受理预检");
        }
        Map<String, Object> actualVariables = variables == null ? Map.of() : variables;
        if (!grant.clientId().equals(clientId) || !grant.message().equals(message) || !grant.variables().equals(actualVariables)) {
            throw new IllegalArgumentException("案件材料上传授权与受理信息不一致");
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        grants.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private String required(String value, String name, int maxLength) {
        String checked = value == null ? "" : value.trim();
        if (checked.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        if (checked.length() > maxLength) {
            throw new IllegalArgumentException(name + " 过长");
        }
        return checked;
    }

    public record UploadAuthorization(String token, String expiresAt) { }
    private record Grant(String clientId, String message, Map<String, Object> variables, Instant expiresAt) { }
}
