package cn.apple.domain.agent.service.armory;

import cn.apple.domain.agent.model.entity.ArmoryCommandEntity;
import cn.apple.domain.agent.model.valobj.AiAgentEnumVO;
import cn.apple.domain.agent.service.armory.business.data.ILordDataStrategy;
import cn.apple.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.applesheep.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * @author _apple_sheep_
 * @description
 * @date 2025/12/10
 * @Copyright github:Yuiang777
 */
@Slf4j
@Service
public class RootNode extends AbstractArmorySupport{


    private final Map<String, ILordDataStrategy> lordDataStrategyMap;

    public RootNode(Map<String, ILordDataStrategy> lordDataStrategyMap) {
        this.lordDataStrategyMap = lordDataStrategyMap;
    }

    @Override
    protected void multiThread(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {
        String command = armoryCommandEntity.getCommandType();

        AiAgentEnumVO byCode = AiAgentEnumVO.getByCode(command);
        String dataName = byCode.getLoadDataStrategy();


        ILordDataStrategy iLordDataStrategy = lordDataStrategyMap.get(dataName);
        iLordDataStrategy.loadData(armoryCommandEntity, dynamicContext);
    }

    @Override
    protected String doApply(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return router(armoryCommandEntity, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }
}
