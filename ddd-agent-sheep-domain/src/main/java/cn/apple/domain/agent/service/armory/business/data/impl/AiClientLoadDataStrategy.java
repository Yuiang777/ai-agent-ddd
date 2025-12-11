package cn.apple.domain.agent.service.armory.business.data.impl;

import cn.apple.domain.agent.adapter.repository.IAgentRepository;
import cn.apple.domain.agent.model.entity.ArmoryCommandEntity;
import cn.apple.domain.agent.model.valobj.*;
import cn.apple.domain.agent.service.armory.business.data.ILordDataStrategy;
import cn.apple.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @author _apple_sheep_
 * @description
 * @date 2025/12/10
 * @Copyright github:Yuiang777
 */
@Slf4j
@Service("aiClientLoadDataStrategy")
public class AiClientLoadDataStrategy implements ILordDataStrategy{

    @Resource
    private IAgentRepository repository;

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;
    @Override
    public void loadData(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) {

        List<String> clientIdList = armoryCommandEntity.getCommandIdList();

        CompletableFuture<List<AiClientApiVO>> aiClientApiListFuture = CompletableFuture.supplyAsync(() -> {
            log.info("查询配置数据(ai_client_api) {}", clientIdList);
            return repository.queryAiClientApiVOListByClientIds(clientIdList);
        }, threadPoolExecutor);

        CompletableFuture<List<AiClientModelVO>> aiClientModelListFuture = CompletableFuture.supplyAsync(() -> {
            log.info("查询配置数据(ai_client_model) {}", clientIdList);
            return repository.AiClientModelVOByClientIds(clientIdList);
        }, threadPoolExecutor);

        CompletableFuture<List<AiClientToolMcpVO>> aiClientToolMcpListFuture = CompletableFuture.supplyAsync(() -> {
            log.info("查询配置数据(ai_client_tool_mcp) {}", clientIdList);
            return repository.AiClientToolMcpVOByClientIds(clientIdList);
        }, threadPoolExecutor);

        CompletableFuture<List<AiClientSystemPromptVO>> aiClientSystemPromptListFuture = CompletableFuture.supplyAsync(() -> {
            log.info("查询配置数据(ai_client_system_prompt) {}", clientIdList);
            return repository.AiClientSystemPromptVOByClientIds(clientIdList);
        }, threadPoolExecutor);

        CompletableFuture<List<AiClientAdvisorVO>> aiClientAdvisorListFuture = CompletableFuture.supplyAsync(() -> {
            log.info("查询配置数据(ai_client_advisor) {}", clientIdList);
            return repository.AiClientAdvisorVOByClientIds(clientIdList);
        }, threadPoolExecutor);

        CompletableFuture<List<AiClientVO>> aiClientListFuture = CompletableFuture.supplyAsync(() -> {
            log.info("查询配置数据(ai_client) {}", clientIdList);
            return repository.AiClientVOByClientIds(clientIdList);
        }, threadPoolExecutor);
    }
}
