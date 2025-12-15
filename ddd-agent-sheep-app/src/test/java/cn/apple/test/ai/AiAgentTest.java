package cn.apple.test.ai;


import cn.apple.test.advisors.RagAnswerAdvisor;
import com.alibaba.fastjson.JSON;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class AiAgentTest {

    private ChatModel chatModel;
    private ChatClient chatClient;

    @Resource
    private PgVectorStore vectorStore;

    public static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";
    public static final String CHAT_MEMORY_RETRIEVE_SIZE_KEY = "chat_memory_response_size";

    /**
     * ===========================================================
     *  核心修复项：
     *  （1）删除 stdio MCP（npx）
     *  （2）仅使用 SSE MCP：8101 和 8102
     * ===========================================================
     */
    @Before
    public void init() {

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl("https://yinli.one/")
                .apiKey("sk-3ghZhkt2KtbJUYU5RXcBrPQ5Q6lPyi8fMrHV7MFWN9iqUasK")
                .embeddingsPath("text-embedding-ada-002")
                .build();

        // ✔ 只加载 SSE MCP
        SyncMcpToolCallbackProvider toolProvider =
                new SyncMcpToolCallbackProvider(sseMcpClient01(), sseMcpClient02());

        chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(
                        OpenAiChatOptions.builder()
                                .model("gpt-4.1-mini")
                                .toolCallbacks(toolProvider.getToolCallbacks())
                                .build()
                )
                .build();

        chatClient = ChatClient.builder(chatModel)
                .defaultSystem("你是 AI Agent ...")
                .defaultAdvisors(
                        PromptChatMemoryAdvisor.builder(
                                MessageWindowChatMemory.builder()
                                        .maxMessages(100)
                                        .build()
                        ).build(),

                        new RagAnswerAdvisor(vectorStore, SearchRequest.builder()
                                .topK(5)
                                .filterExpression("knowledge == 'article-prompt-words'")
                                .build()),

                        SimpleLoggerAdvisor.builder().build()
                )
                .build();
    }

    // --------------------------------------------------------------------
    //  测试 1：流式输出
    // --------------------------------------------------------------------
    @Test
    public void test_chat_model_stream_01() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(1);

        Prompt prompt = Prompt.builder()
                .messages(new UserMessage("有哪些工具可以使用"))
                .build();

        Flux<ChatResponse> stream = chatModel.stream(prompt);

        stream.subscribe(
                chatResponse -> {
                    AssistantMessage output = chatResponse.getResult().getOutput();
                    log.info("测试结果: {}", JSON.toJSONString(output));
                },
                Throwable::printStackTrace,
                () -> {
                    countDownLatch.countDown();
                    System.out.println("Stream completed");
                }
        );

        countDownLatch.await();
    }

    // --------------------------------------------------------------------
    //  测试 2：非流式
    // --------------------------------------------------------------------
    @Test
    public void test_chat_model_call() {
        Prompt prompt = Prompt.builder()
                .messages(new UserMessage("有哪些工具可以使用"))
                .build();

        ChatResponse chatResponse = chatModel.call(prompt);
        log.info("测试结果(call):{}", JSON.toJSONString(chatResponse));
    }

    // --------------------------------------------------------------------
    //  测试 3：带 ChatClient（记忆、RAG）
    // --------------------------------------------------------------------
    @Test
    public void test_02() {
        String userInput = "王大瓜今年几岁？";

        System.out.println("\n>>> QUESTION: " + userInput);

        System.out.println("\n>>> ASSISTANT: " + chatClient
                .prompt(userInput)
                .system(s -> s.param("current_date", LocalDate.now().toString()))
                .call()
                .content());
    }

    // --------------------------------------------------------------------
    //  测试 4：完整的 AI Agent 流程
    // --------------------------------------------------------------------
    @Test
    public void test_client03() {

        SyncMcpToolCallbackProvider toolProvider =
                new SyncMcpToolCallbackProvider(sseMcpClient01(), sseMcpClient02());

        ChatModel safeChatModel = OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder()
                        .baseUrl("https://yinli.one/")
                        .apiKey("sk-3ghZhkt2KtbJUYU5RXcBrPQ5Q6lPyi8fMrHV7MFWN9iqUasK")
                        .embeddingsPath("text-embedding-ada-002")
                        .build())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4.1-mini")
                        .toolCallbacks(toolProvider.getToolCallbacks())
                        .build())
                .build();

        /**
         * step 1：先用提示词优化专家生成完整 prompt
         */
        ChatClient chatClient01 = ChatClient.builder(safeChatModel)
                .defaultSystem("你是一个专业的AI提示词优化专家...")
                .defaultAdvisors(
                        PromptChatMemoryAdvisor.builder(
                                MessageWindowChatMemory.builder()
                                        .maxMessages(100)
                                        .build()
                        ).build(),

                        new RagAnswerAdvisor(vectorStore, SearchRequest.builder()
                                .topK(5)
                                .filterExpression("knowledge == 'article-prompt-words'")
                                .build())
                )
                .defaultOptions(OpenAiChatOptions.builder().model("gpt-4.1-mini").build())
                .build();

        String optimizedPrompt = chatClient01
                .prompt("生成一篇文章")
                .call()
                .content();

        System.out.println("\n>>> 优化后的 Prompt：");
        System.out.println(optimizedPrompt);

        /**
         * step 2：调用 CSDN + 微信 Agent
         */
        ChatClient chatClient02 = ChatClient.builder(safeChatModel)
                .defaultSystem("""
                        你是一个 AI Agent 智能体，可以根据用户输入信息生成文章，
                        并发送到 CSDN 平台以及完成微信公众号通知。
                        """)
                .defaultAdvisors(
                        PromptChatMemoryAdvisor.builder(
                                MessageWindowChatMemory.builder()
                                        .maxMessages(100)
                                        .build()).build(),
                        new SimpleLoggerAdvisor()
                )
                .defaultOptions(OpenAiChatOptions.builder().model("gpt-4.1-mini").build())
                .build();

        String userInput = "生成一篇文章，要求如下：\n" + optimizedPrompt;

        String result = chatClient02
                .prompt(userInput)
                .call()
                .content();

        System.out.println("\n>>> ASSISTANT: " + result);
    }

    // --------------------------------------------------------------------
    //  MCP：SSE 方式（适用于 Docker 8101、8102）
    // --------------------------------------------------------------------

    public McpSyncClient sseMcpClient01() {
        HttpClientSseClientTransport transport =
                HttpClientSseClientTransport.builder("http://127.0.0.1:8102").build();

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofMinutes(10))
                .build();

        System.out.println("SSE MCP #1 Initialized: " + client.initialize());
        return client;
    }

    public McpSyncClient sseMcpClient02() {
        HttpClientSseClientTransport transport =
                HttpClientSseClientTransport.builder("http://127.0.0.1:8101").build();

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofMinutes(10))
                .build();

        System.out.println("SSE MCP #2 Initialized: " + client.initialize());
        return client;
    }

}

