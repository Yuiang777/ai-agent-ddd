package cn.apple.domain.agent.service.armory.factory.element;

import cn.apple.domain.legal.model.valobj.LegalEvidence;
import cn.apple.domain.legal.model.valobj.LegalRetrievalRequest;
import cn.apple.domain.legal.service.LegalHybridRetrievalService;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author _apple_sheep_
 * @description
 * @date 2026/2/15
 * @Copyright github:Yuiang777
 */
public class RagAnswerAdvisor implements BaseAdvisor {


    private final VectorStore vectorStore;
    private final LegalHybridRetrievalService legalHybridRetrievalService;
    private final SearchRequest searchRequest;
    private final String userTextAdvise;

    public RagAnswerAdvisor(VectorStore vectorStore, SearchRequest searchRequest) {
        this(vectorStore, null, searchRequest);
    }

    public RagAnswerAdvisor(VectorStore vectorStore, LegalHybridRetrievalService legalHybridRetrievalService, SearchRequest searchRequest) {
        this.vectorStore = vectorStore;
        this.legalHybridRetrievalService = legalHybridRetrievalService;
        this.searchRequest = searchRequest;
        this.userTextAdvise = """
                - 回答必须优先基于【检索证据】。
                - 涉及法律依据时，每个关键结论都必须引用证据编号，并能落到 law_name/article_no/clause_no。
                - 不要把“第三条”和“第三条之二”混为一谈；引用时保留完整法条编号。
                - 如果证据不足以支撑结论，明确说明当前知识库依据不足，不要编造法条或引用。
                """;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(this.before(chatClientRequest, callAdvisorChain));
        return this.after(chatClientResponse, callAdvisorChain);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        return BaseAdvisor.super.adviseStream(chatClientRequest, streamAdvisorChain);
    }

    @Override
    public String getName() {
        return BaseAdvisor.super.getName();
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        HashMap<String, Object> context = new HashMap(chatClientRequest.context());

        String userText = chatClientRequest.prompt().getUserMessage().getText();

        String query = (new PromptTemplate(userText)).render();
        
        SearchRequest searchRequestToUse = SearchRequest.from(this.searchRequest).query(query).filterExpression(this.doGetFilterExpression(context)).build();
        List<LegalEvidence> evidences;
        String retrievalError = null;
        try {
            evidences = retrieveEvidence(query, context, searchRequestToUse);
        } catch (Exception e) {
            evidences = List.of();
            retrievalError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        context.put("qa_retrieved_documents", evidences);
        if (retrievalError != null) {
            context.put("qa_retrieval_error", retrievalError);
        }

        String evidenceContext = formatEvidence(evidences);
        String scopeContext = formatScope(evidences);
        Map<String, Object> advisedUserParams = new HashMap(chatClientRequest.context());
        advisedUserParams.put("question_answer_context", evidenceContext);
        advisedUserParams.put("qa_retrieved_documents", evidences);
        if (!scopeContext.isBlank()) {
            advisedUserParams.put("qa_scope_context", scopeContext);
        }
        if (retrievalError != null) {
            advisedUserParams.put("qa_retrieval_error", retrievalError);
        }

        String retrievalContextText = evidenceContext == null || evidenceContext.isBlank() ? "（空）" : evidenceContext;
        String appendText = System.lineSeparator()
                + System.lineSeparator()
                + this.userTextAdvise
                + System.lineSeparator()
                + "【检索范围判断】"
                + System.lineSeparator()
                + (scopeContext.isBlank() ? "（未命中明确专门领域，按通用法律范围检索）" : scopeContext)
                + System.lineSeparator()
                + "【检索证据】"
                + System.lineSeparator()
                + retrievalContextText;

        Prompt prompt = chatClientRequest.prompt().copy()
                .augmentSystemMessage(existing -> new SystemMessage(existing.getText() + appendText));

        return ChatClientRequest.builder()
                .prompt(prompt)
                .context(advisedUserParams)
                .build();


    }

    private List<LegalEvidence> retrieveEvidence(String query, HashMap<String, Object> context, SearchRequest searchRequestToUse) {
        if (legalHybridRetrievalService != null) {
            return legalHybridRetrievalService.retrieve(new LegalRetrievalRequest(
                    query,
                    filterExpressionText(context),
                    searchRequest.getTopK(),
                    Math.max(searchRequest.getTopK() * 3, 12),
                    Math.max(searchRequest.getTopK() * 3, 12)
            ));
        }
        return vectorStore.similaritySearch(searchRequestToUse).stream()
                .map(document -> new LegalEvidence(
                        String.valueOf(document.getMetadata().getOrDefault("id", "")),
                        document.getText(),
                        String.valueOf(document.getMetadata().getOrDefault("doc_id", "")),
                        String.valueOf(document.getMetadata().getOrDefault("doc_name", "")),
                        String.valueOf(document.getMetadata().getOrDefault("law_name", "")),
                        String.valueOf(document.getMetadata().getOrDefault("article_no", "")),
                        String.valueOf(document.getMetadata().getOrDefault("clause_no", "")),
                        parseInt(document.getMetadata().get("chunk_index")),
                        String.valueOf(document.getMetadata().getOrDefault("source_path", "")),
                        0.0,
                        document.getMetadata()))
                .toList();
    }

    private String filterExpressionText(HashMap<String, Object> context) {
        if (context.containsKey("qa_filter_expression") && StringUtils.hasText(context.get("qa_filter_expression").toString())) {
            return context.get("qa_filter_expression").toString();
        }
        return searchRequest.getFilterExpression() == null ? null : searchRequest.getFilterExpression().toString();
    }

    private String formatEvidence(List<LegalEvidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < evidences.size(); i++) {
            LegalEvidence evidence = evidences.get(i);
            builder.append("[证据").append(i + 1).append("] ")
                    .append("law_name=").append(value(evidence.lawName())).append("; ")
                    .append("article_no=").append(value(evidence.articleNo())).append("; ")
                    .append("clause_no=").append(value(evidence.clauseNo())).append("; ")
                    .append("source=").append(value(evidence.docName())).append("; ")
                    .append("citation=").append(evidence.citation())
                    .append(System.lineSeparator())
                    .append(evidence.text())
                    .append(System.lineSeparator());
        }
        return builder.toString();
    }

    private String formatScope(List<LegalEvidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return "";
        }
        Map<String, Object> metadata = evidences.get(0).metadata();
        if (metadata == null || !metadata.containsKey("scope_code")) {
            return "";
        }
        String scopeName = String.valueOf(metadata.getOrDefault("scope_name", ""));
        String reason = String.valueOf(metadata.getOrDefault("scope_reason", ""));
        Object lawHints = metadata.get("scope_law_hints");
        return "范围=" + scopeName + "；关联法律=" + (lawHints == null ? "" : lawHints) + "；原因=" + reason;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private int parseInt(Object value) {
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private Filter.Expression doGetFilterExpression(HashMap<String, Object> context) {
        return context.containsKey("qa_filter_expression") && StringUtils.hasText(context.get("qa_filter_expression").toString()) ? (new FilterExpressionTextParser()).parse(context.get("qa_filter_expression").toString()) : this.searchRequest.getFilterExpression();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        ChatResponse.Builder chatResponse = ChatResponse.builder().from(chatClientResponse.chatResponse());
        chatResponse.metadata("qa_retrieved_documents", chatClientResponse.context().get("qa_retrieved_documents"));
        return ChatClientResponse.builder()
                .chatResponse(chatResponse.build())
                .context(chatClientResponse.context())
                .build();
    }

    @Override
    public Scheduler getScheduler() {
        return BaseAdvisor.super.getScheduler();
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
