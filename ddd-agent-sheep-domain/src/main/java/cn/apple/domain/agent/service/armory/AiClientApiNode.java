package cn.apple.domain.agent.service.armory;

import cn.apple.domain.agent.model.entity.ArmoryCommandEntity;
import cn.apple.domain.agent.model.valobj.AiAgentEnumVO;
import cn.apple.domain.agent.model.valobj.AiClientApiVO;
import cn.apple.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.applesheep.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * @author _apple_sheep_
 * @description
 * @date 2025/12/12
 * @Copyright github:Yuiang777
 */
@Slf4j
@Service
public class AiClientApiNode extends AbstractArmorySupport{

    private final AiClientToolMcpNode aiClientToolMcpNode;

    public AiClientApiNode(AiClientToolMcpNode aiClientToolMcpNode) {
        this.aiClientToolMcpNode = aiClientToolMcpNode;
    }

    @Override
    protected String doApply(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        List<AiClientApiVO> aiClientApiVOList = dynamicContext.getValue(AiAgentEnumVO.AI_CLIENT_API.getDataName());

        if (aiClientApiVOList == null || aiClientApiVOList.isEmpty()) {
            log.warn("没有需要被初始化的 ai client api");
            return router(armoryCommandEntity, dynamicContext);
        }
        for (AiClientApiVO aiClientApiVO : aiClientApiVOList) {
            String rawBaseUrl = sanitize(aiClientApiVO.getBaseUrl());
            String apiKey = sanitize(aiClientApiVO.getApiKey());
            String rawCompletionsPath = sanitize(aiClientApiVO.getCompletionsPath());
            String rawEmbeddingsPath = sanitize(aiClientApiVO.getEmbeddingsPath());

            NormalizedEndpoint completionsEndpoint = normalize(rawBaseUrl, rawCompletionsPath);
            NormalizedEndpoint embeddingsEndpoint = normalize(rawBaseUrl, rawEmbeddingsPath);

            String baseUrl = completionsEndpoint.baseUrl();
            String completionsPath = completionsEndpoint.path();
            String embeddingsPath = embeddingsEndpoint.path();

            OpenAiApi openAiApi = OpenAiApi.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .completionsPath(completionsPath)
                    .embeddingsPath(embeddingsPath)
                    .restClientBuilder(openAiRestClientBuilder())
                    .build();

            registerBean(AiAgentEnumVO.AI_CLIENT_API.getBeanName(aiClientApiVO.getApiId()), OpenAiApi.class, openAiApi);
        }
        return router(armoryCommandEntity, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return aiClientToolMcpNode;
    }

    @Override
    public String beanName(String beanId){
        return AiAgentEnumVO.AI_CLIENT_API.getBeanName(beanId);
    }

    @Override
    public String dataName(){
        return AiAgentEnumVO.AI_CLIENT_API.getDataName();
    }

    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("`") && v.endsWith("`")) {
            v = v.substring(1, v.length() - 1).trim();
        }
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1).trim();
        }
        return v;
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

    private NormalizedEndpoint normalize(String baseUrl, String pathOrUrl) {
        String normalizedBaseUrl = stripTrailingSlash(sanitize(baseUrl));
        String normalizedPath = sanitize(pathOrUrl);

        if (normalizedPath == null || normalizedPath.isBlank()) {
            return new NormalizedEndpoint(normalizedBaseUrl, null);
        }

        if (normalizedPath.startsWith("http://") || normalizedPath.startsWith("https://")) {
            try {
                URI uri = URI.create(normalizedPath);
                String fullBase = uri.getScheme() + "://" + uri.getAuthority();
                String fullPath = uri.getRawPath();
                return new NormalizedEndpoint(stripTrailingSlash(fullBase), ensureLeadingSlash(stripTrailingSlash(fullPath)));
            } catch (Exception e) {
                return new NormalizedEndpoint(normalizedBaseUrl, null);
            }
        }

        String p = ensureLeadingSlash(normalizedPath);
        if (normalizedBaseUrl != null && normalizedBaseUrl.endsWith("/v1") && p.startsWith("/v1/")) {
            p = p.substring(3);
        }
        p = stripTrailingSlash(p);
        return new NormalizedEndpoint(normalizedBaseUrl, p);
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

    private String ensureLeadingSlash(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private record NormalizedEndpoint(String baseUrl, String path) {
    }
}
