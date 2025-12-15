package cn.apple.domain.agent.service.armory.factory;

import cn.apple.domain.agent.model.entity.ArmoryCommandEntity;
import cn.apple.domain.agent.service.armory.RootNode;
import cn.applesheep.wrench.design.framework.tree.StrategyHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * @author _apple_sheep_
 * @description
 * @date 2025/12/10
 * @Copyright github:Yuiang777
 */
@Service
public class DefaultArmoryStrategyFactory {

    private final RootNode rootNode;

    public DefaultArmoryStrategyFactory(RootNode rootNode) {
        this.rootNode = rootNode;
    }

    public StrategyHandler<ArmoryCommandEntity, DynamicContext, String> armoryStrategyHandler(){
        return rootNode;
    }


    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext{
        private Map<String, Object> dataObject = new HashMap<>();

        public <T> void setValue(String key, T value){
            dataObject.put(key, value);
        }

        public <T> T getValue(String key){
            return (T)dataObject.get(key);
        }
    }
}
