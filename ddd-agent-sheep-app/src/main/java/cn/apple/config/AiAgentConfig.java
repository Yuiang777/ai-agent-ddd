package cn.apple.config;

import cn.apple.domain.legal.model.valobj.LegalKnowledgeMetadata;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Configuration
@ConditionalOnProperty(name = "app.infrastructure.enabled", havingValue = "true")
public class AiAgentConfig {

    @Bean("vectorStore")
    public PgVectorStore pgVectorStore(@Value("${spring.ai.embedding.base-url:${spring.ai.openai.base-url}}") String embeddingBaseUrl,
                                       @Value("${spring.ai.embedding.api-key:${spring.ai.openai.api-key}}") String embeddingApiKey,
                                       @Value("${spring.ai.embedding.model:}") String embeddingModel,
                                       @Value("${spring.ai.embedding.dimensions:0}") int embeddingDimensions,
                                       @Value("${app.legal.knowledge.vector-table:"
                                               + LegalKnowledgeMetadata.DEFAULT_VECTOR_TABLE + "}") String vectorTable,
                                       @Qualifier("mysqlDataSource") DataSource mysqlDataSource,
                                       @Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate) {

        EmbeddingConfig configFromDb = loadEmbeddingConfig(mysqlDataSource);
        String baseUrl = configFromDb != null && notBlank(configFromDb.baseUrl()) ? configFromDb.baseUrl() : embeddingBaseUrl;
        String apiKey = configFromDb != null && notBlank(configFromDb.apiKey()) ? configFromDb.apiKey() : embeddingApiKey;
        String model = configFromDb != null && notBlank(configFromDb.model()) ? configFromDb.model() : embeddingModel;

        baseUrl = sanitize(baseUrl);
        baseUrl = stripTrailingSlash(baseUrl);
        apiKey = sanitize(apiKey);
        model = sanitize(model);

        baseUrl = validateBaseUrl(baseUrl);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(openAiRestClientBuilder())
                .build();

        OpenAiEmbeddingModel openAiEmbeddingModel = new OpenAiEmbeddingModel(
                openAiApi, MetadataMode.EMBED, embeddingOptions(model, embeddingDimensions));

        var vectorStoreBuilder = PgVectorStore.builder(jdbcTemplate, openAiEmbeddingModel)
                .vectorTableName(validateVectorTableName(vectorTable));
        if (embeddingDimensions > 0) {
            vectorStoreBuilder.dimensions(embeddingDimensions);
        }
        return vectorStoreBuilder.build();
    }

    OpenAiEmbeddingOptions embeddingOptions(String model, int dimensions) {
        if (dimensions < 0) {
            throw new IllegalArgumentException("spring.ai.embedding.dimensions must be zero or positive");
        }
        var optionsBuilder = OpenAiEmbeddingOptions.builder()
                .model(notBlank(model) ? model.trim() : OpenAiApi.DEFAULT_EMBEDDING_MODEL);
        if (dimensions > 0) {
            optionsBuilder.dimensions(dimensions);
        }
        return optionsBuilder.build();
    }

    private EmbeddingConfig loadEmbeddingConfig(DataSource mysqlDataSource) {
        try {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(mysqlDataSource);
            return jdbcTemplate.query(
                    "select base_url, api_key, model from ai_embedding_config where status = 1 order by update_time desc limit 1",
                    rs -> rs.next()
                            ? new EmbeddingConfig(sanitize(rs.getString("base_url")), sanitize(rs.getString("api_key")), sanitize(rs.getString("model")))
                            : null
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        while (v.startsWith("`") || v.startsWith("\"") || v.startsWith("'")) {
            v = v.substring(1).trim();
        }
        while (v.endsWith("`") || v.endsWith("\"") || v.endsWith("'")) {
            v = v.substring(0, v.length() - 1).trim();
        }
        v = v.replace("\uFEFF", "").trim();
        return v.isEmpty() ? null : v;
    }

    private String stripTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        while (v.endsWith("/") && v.length() > 1) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private String validateBaseUrl(String baseUrl) {
        if (!notBlank(baseUrl)) {
            throw new IllegalStateException("Embedding baseUrl 不能为空：请在配置或 ai_embedding_config.base_url 中提供 http(s) 地址");
        }
        String v = baseUrl.trim();
        if (v.contains("{") || v.contains("}")) {
            throw new IllegalStateException("Embedding baseUrl 不合法（包含 { 或 }，会被 Spring 当作 URI 模板解析）：baseUrl=" + v);
        }
        URI uri;
        try {
            uri = URI.create(v);
        } catch (Exception e) {
            throw new IllegalStateException("Embedding baseUrl 不合法（无法解析为 URI）：baseUrl=" + v, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalStateException("Embedding baseUrl 缺少协议或协议不支持（仅支持 http/https）：baseUrl=" + v);
        }
        return v;
    }

    private String validateVectorTableName(String value) {
        String checked = value == null ? "" : value.trim();
        if (!checked.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalStateException("app.legal.knowledge.vector-table 必须是小写 SQL 标识符");
        }
        return checked;
    }

    private RestClient.Builder openAiRestClientBuilder() {
        return RestClient.builder()
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .messageConverters(converters -> {
                    for (HttpMessageConverter<?> converter : converters) {
                        if (converter instanceof MappingJackson2HttpMessageConverter jacksonConverter) {
                            List<MediaType> supportedMediaTypes = new ArrayList<>(jacksonConverter.getSupportedMediaTypes());
                            if (!supportedMediaTypes.contains(MediaType.APPLICATION_OCTET_STREAM)) {
                                supportedMediaTypes.add(MediaType.APPLICATION_OCTET_STREAM);
                            }
                            jacksonConverter.setSupportedMediaTypes(supportedMediaTypes);
                        }
                    }
                });
    }

    private record EmbeddingConfig(String baseUrl, String apiKey, String model) {
    }

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return new TokenTextSplitter();
    }

}
