package cn.apple.domain.agent.service.armory;

import cn.apple.domain.agent.model.entity.ArmoryCommandEntity;
import cn.apple.domain.agent.model.valobj.AiAgentEnumVO;
import cn.apple.domain.agent.model.valobj.AiClientAdvisorTypeEnumVO;
import cn.apple.domain.agent.model.valobj.AiClientAdvisorVO;
import cn.apple.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.apple.domain.legal.service.LegalHybridRetrievalService;
import cn.applesheep.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author _apple_sheep_
 * @description
 * @date 2026/2/13
 * @Copyright github:Yuiang777
 */
@Slf4j
@Service
public class AiClientAdvisorNode extends AbstractArmorySupport{

    @Resource
    private VectorStore vectorStore;
    @Resource
    private LegalHybridRetrievalService legalHybridRetrievalService;
    @Resource
    private AiClientNode aiClientNode;
    @Override
    protected String doApply(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {

        List<AiClientAdvisorVO> aiClientAdvisorVOList = dynamicContext.getValue(dataName());

        if(aiClientAdvisorVOList == null || aiClientAdvisorVOList.isEmpty()){
            log.info("没有需要被初始化的顾问");
            return router(armoryCommandEntity, dynamicContext);
        }

        for(AiClientAdvisorVO aiClientAdvisorVO : aiClientAdvisorVOList){
            Advisor advisor = createAdvisor(aiClientAdvisorVO);
            registerBean(beanName(aiClientAdvisorVO.getAdvisorId()), Advisor.class, advisor);
        }

        return router(armoryCommandEntity, dynamicContext);
    }

    private Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO) {
        String advisorType = aiClientAdvisorVO.getAdvisorType();
        AiClientAdvisorTypeEnumVO byCode = AiClientAdvisorTypeEnumVO.getByCode(advisorType);
        return byCode.createAdvisor(aiClientAdvisorVO, vectorStore, legalHybridRetrievalService);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return aiClientNode;
    }

    @Override
    public String beanName(String beanId){
        return AiAgentEnumVO.AI_CLIENT_ADVISOR.getBeanName(beanId);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT_ADVISOR.getDataName();
    }
}
