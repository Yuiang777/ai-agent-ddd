package cn.apple.domain.agent.service.armory.business.data.impl;

import cn.apple.domain.agent.adapter.repository.IAgentRepository;
import cn.apple.domain.agent.model.entity.ArmoryCommandEntity;
import cn.apple.domain.agent.service.armory.business.data.ILordDataStrategy;
import cn.apple.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import jakarta.annotation.Resource;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @author _apple_sheep_
 * @description
 * @date 2025/12/10
 * @Copyright github:Yuiang777
 */
public class AiClientLoadDataStrategy implements ILordDataStrategy{

    @Resource
    private IAgentRepository agentRepository;

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;
    @Override
    public void loadData(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) {

        List<String> commandIdList = armoryCommandEntity.getCommandIdList();

        CompletableFuture<List<String>> future = CompletableFuture.supplyAsync(() -> {
            return agentRepository.getAgentData(commandIdList);
        }, threadPoolExecutor);
    }
}
