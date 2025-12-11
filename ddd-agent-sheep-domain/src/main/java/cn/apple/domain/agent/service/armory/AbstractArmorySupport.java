package cn.apple.domain.agent.service.armory;

import cn.apple.domain.agent.adapter.repository.IAgentRepository;
import cn.apple.domain.agent.model.entity.ArmoryCommandEntity;
import cn.apple.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.applesheep.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;

/**
 * @author _apple_sheep_
 * @description
 * @date 2025/12/10
 * @Copyright github:Yuiang777
 */
public abstract class AbstractArmorySupport extends AbstractMultiThreadStrategyRouter<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext,String> {


    private final Logger log = LoggerFactory.getLogger(AbstractArmorySupport.class);

    @Resource
    protected ApplicationContext applicationContext;

    @Resource
    protected ThreadPoolExecutor threadPoolExecutor;

    @Resource
    protected IAgentRepository repository;

    @Override
    protected void multiThread(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {
        // 缺省的
    }

}
