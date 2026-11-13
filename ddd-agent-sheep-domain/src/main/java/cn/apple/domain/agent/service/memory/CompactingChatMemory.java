package cn.apple.domain.agent.service.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class CompactingChatMemory implements ChatMemory {

    public static final String SUMMARY_PREFIX = "[历史对话压缩摘要]";
    private static final String OMITTED_MARKER = " ...[已压缩]... ";

    private final Map<String, ConversationState> conversations = new ConcurrentHashMap<>();
    private final AtomicLong accessSequence = new AtomicLong();
    private final int maxMessages;
    private final int maxCharacters;
    private final int summaryCharacters;
    private final int recentMessages;
    private final int maxConversations;
    private final long conversationTtlMs;

    public CompactingChatMemory(int maxMessages,
                                int maxCharacters,
                                int summaryCharacters,
                                int recentMessages) {
        this(maxMessages, maxCharacters, summaryCharacters, recentMessages, 1000, 3600);
    }

    public CompactingChatMemory(int maxMessages,
                                int maxCharacters,
                                int summaryCharacters,
                                int recentMessages,
                                int maxConversations,
                                long conversationTtlSeconds) {
        this.maxMessages = clamp(maxMessages, 4, 200);
        this.maxCharacters = clamp(maxCharacters, 1000, 200000);
        this.summaryCharacters = clamp(summaryCharacters, 200, Math.max(200, this.maxCharacters / 2));
        this.recentMessages = clamp(recentMessages, 2, Math.max(2, this.maxMessages - 1));
        this.maxConversations = clamp(maxConversations, 10, 100000);
        this.conversationTtlMs = clampLong(conversationTtlSeconds, 60, 604800) * 1000L;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        String key = conversationKey(conversationId);
        long now = System.currentTimeMillis();
        removeExpired(now);
        conversations.compute(key, (ignored, existing) -> {
            List<Message> combined = new ArrayList<>(existing == null ? List.of() : existing.messages());
            messages.stream().filter(message -> message != null).forEach(combined::add);
            return new ConversationState(compact(combined), now, accessSequence.incrementAndGet());
        });
        enforceConversationLimit();
    }

    @Override
    public List<Message> get(String conversationId) {
        String key = conversationKey(conversationId);
        ConversationState state = conversations.get(key);
        if (state == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        if (now - state.lastAccessMs() > conversationTtlMs) {
            conversations.remove(key, state);
            return List.of();
        }
        ConversationState touched = new ConversationState(state.messages(), now, accessSequence.incrementAndGet());
        conversations.replace(key, state, touched);
        return state.messages();
    }

    @Override
    public void clear(String conversationId) {
        conversations.remove(conversationKey(conversationId));
    }

    private List<Message> compact(List<Message> messages) {
        if (messages.size() <= maxMessages && totalCharacters(messages) <= maxCharacters) {
            return List.copyOf(messages);
        }

        StringBuilder summarySource = new StringBuilder();
        List<Message> ordinary = new ArrayList<>();
        for (Message message : messages) {
            if (isSummary(message)) {
                appendSummaryText(summarySource, message.getText());
            } else {
                ordinary.add(message);
            }
        }

        int keep = Math.min(recentMessages, ordinary.size());
        int splitAt = ordinary.size() - keep;
        for (int i = 0; i < splitAt; i++) {
            appendMessage(summarySource, ordinary.get(i));
        }
        List<Message> recent = new ArrayList<>(ordinary.subList(splitAt, ordinary.size()));

        String summaryText = buildSummary(summarySource.toString());
        while (estimatedCharacters(summaryText, recent) > maxCharacters && recent.size() > 2) {
            appendMessage(summarySource, recent.remove(0));
            summaryText = buildSummary(summarySource.toString());
        }

        int remainingForRecent = Math.max(200, maxCharacters - summaryText.length());
        recent = compressRecentMessages(recent, remainingForRecent);

        List<Message> compacted = new ArrayList<>();
        if (!summaryText.isBlank()) {
            compacted.add(new SystemMessage(summaryText));
        }
        compacted.addAll(recent);
        return List.copyOf(compacted);
    }

    private List<Message> compressRecentMessages(List<Message> messages, int budget) {
        if (totalCharacters(messages) <= budget || messages.isEmpty()) {
            return messages;
        }
        List<Message> compressed = new ArrayList<>();
        int remaining = budget;
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            int messagesLeft = messages.size() - i;
            int allowance = Math.max(80, remaining / messagesLeft);
            String text = compactText(message.getText(), allowance);
            compressed.add(copyWithText(message, text));
            remaining = Math.max(0, remaining - text.length());
        }
        return compressed;
    }

    private Message copyWithText(Message message, String text) {
        if (message instanceof UserMessage) {
            return new UserMessage(text);
        }
        if (message instanceof AssistantMessage) {
            return new AssistantMessage(text);
        }
        if (message instanceof SystemMessage) {
            return new SystemMessage(text);
        }
        return new SystemMessage("[历史工具结果] " + text);
    }

    private String buildSummary(String source) {
        String normalized = source.replaceAll("[\\t ]+", " ")
                .replaceAll("\\n{3,}", "\\n\\n")
                .strip();
        if (normalized.isBlank()) {
            return "";
        }
        int bodyBudget = Math.max(80, summaryCharacters - SUMMARY_PREFIX.length() - 1);
        return SUMMARY_PREFIX + "\n" + compactText(normalized, bodyBudget);
    }

    private String compactText(String value, int maxLength) {
        String text = value == null ? "" : value.strip();
        if (text.length() <= maxLength) {
            return text;
        }
        if (maxLength <= OMITTED_MARKER.length() + 20) {
            return text.substring(0, maxLength);
        }
        int contentLength = maxLength - OMITTED_MARKER.length();
        int headLength = contentLength * 2 / 5;
        int tailLength = contentLength - headLength;
        return text.substring(0, headLength).stripTrailing()
                + OMITTED_MARKER
                + text.substring(text.length() - tailLength).stripLeading();
    }

    private void appendSummaryText(StringBuilder target, String summary) {
        String body = summary == null ? "" : summary.strip();
        if (body.startsWith(SUMMARY_PREFIX)) {
            body = body.substring(SUMMARY_PREFIX.length()).strip();
        }
        appendLine(target, body);
    }

    private void appendMessage(StringBuilder target, Message message) {
        String text = message.getText() == null ? "" : message.getText().replaceAll("\\s+", " ").strip();
        if (!text.isBlank()) {
            appendLine(target, role(message) + ": " + text);
        }
    }

    private void appendLine(StringBuilder target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append('\n');
        }
        target.append(value.strip());
    }

    private String role(Message message) {
        if (message instanceof UserMessage) return "用户";
        if (message instanceof AssistantMessage) return "助手";
        if (message instanceof SystemMessage) return "系统";
        return "工具";
    }

    private boolean isSummary(Message message) {
        return message instanceof SystemMessage
                && message.getText() != null
                && message.getText().startsWith(SUMMARY_PREFIX);
    }

    private int estimatedCharacters(String summary, List<Message> messages) {
        return summary.length() + totalCharacters(messages);
    }

    private int totalCharacters(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            if (message != null && message.getText() != null) {
                total += message.getText().length();
            }
        }
        return total;
    }

    private String conversationKey(String conversationId) {
        return conversationId == null || conversationId.isBlank()
                ? ChatMemory.DEFAULT_CONVERSATION_ID
                : conversationId;
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private long clampLong(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private void removeExpired(long now) {
        conversations.entrySet().removeIf(entry -> now - entry.getValue().lastAccessMs() > conversationTtlMs);
    }

    private void enforceConversationLimit() {
        while (conversations.size() > maxConversations) {
            conversations.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().accessSequence()))
                    .ifPresent(entry -> conversations.remove(entry.getKey()));
        }
    }

    private record ConversationState(List<Message> messages, long lastAccessMs, long accessSequence) {
    }
}
