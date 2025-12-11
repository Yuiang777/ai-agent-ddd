package cn.apple.domain.agent.service.armory.business.data.impl;

import cn.apple.domain.agent.adapter.repository.IAgentRepository;
import cn.apple.domain.agent.model.entity.ArmoryCommandEntity;
import cn.apple.domain.agent.model.valobj.AiClientApiVO;
import cn.apple.domain.agent.model.valobj.AiClientModelVO;
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
@Service
public class AiClientModelLoadDataStrategy implements ILordDataStrategy {


    @Resource
    private IAgentRepository repository;

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;
    @Override
    public void loadData(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) {
        List<String> modelIdList = armoryCommandEntity.getCommandIdList();

        CompletableFuture<List<AiClientApiVO>> aiClientApiListFuture = CompletableFuture.supplyAsync(() -> {
            log.info("查询配置数据(ai_client_api) {}", modelIdList);
            return repository.queryAiClientApiVOListByModelIds(modelIdList);
        }, threadPoolExecutor);

        CompletableFuture<List<AiClientModelVO>> aiClientModelListFuture = CompletableFuture.supplyAsync(() -> {
            log.info("查询配置数据(ai_client_model) {}", modelIdList);
            return repository.AiClientModelVOByModelIds(modelIdList);
        }, threadPoolExecutor);
    }
}
