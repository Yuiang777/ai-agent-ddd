package cn.apple.config;

import org.junit.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class AiAgentEmbeddingOptionsTest {

    private final AiAgentConfig configuration = new AiAgentConfig();

    @Test
    public void shouldPreserveProviderDimensionsByDefault() {
        var options = configuration.embeddingOptions(" qwen3-embedding:4b ", 0);

        assertEquals("qwen3-embedding:4b", options.getModel());
        assertNull(options.getDimensions());
    }

    @Test
    public void shouldSendExplicitDimensionsWithSelectedModel() {
        var options = configuration.embeddingOptions("qwen3-embedding:4b", 1536);

        assertEquals("qwen3-embedding:4b", options.getModel());
        assertEquals(Integer.valueOf(1536), options.getDimensions());
    }

    @Test
    public void shouldRetainLibraryDefaultModelWhenNoModelIsConfigured() {
        for (String model : new String[]{null, "", " "}) {
            var options = configuration.embeddingOptions(model, 0);

            assertEquals(OpenAiApi.DEFAULT_EMBEDDING_MODEL, options.getModel());
            assertNull(options.getDimensions());
        }
    }

    @Test
    public void shouldRejectNegativeDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> configuration.embeddingOptions("qwen3-embedding:4b", -1));
    }

    @Test
    public void shouldSendDimensionsForBothQueryAndDocumentEmbedding() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            requestBody.set(mapper.readTree(exchange.getRequestBody()));
            byte[] response = """
                    {"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1,0.2,0.3]}],
                     "model":"test-embedding","usage":{"prompt_tokens":1,"total_tokens":1}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .apiKey("synthetic-test-key").build();
            OpenAiEmbeddingModel model = new OpenAiEmbeddingModel(
                    api, MetadataMode.EMBED, configuration.embeddingOptions("test-embedding", 3));

            assertEquals(3, model.embed("synthetic query").length);
            assertEquals(3, requestBody.get().get("dimensions").asInt());
            assertEquals("test-embedding", requestBody.get().get("model").asText());

            requestBody.set(null);
            assertEquals(3, model.embed(new Document("synthetic document")).length);
            assertEquals(3, requestBody.get().get("dimensions").asInt());
            assertEquals("test-embedding", requestBody.get().get("model").asText());
        } finally {
            server.stop(0);
        }
    }
}
