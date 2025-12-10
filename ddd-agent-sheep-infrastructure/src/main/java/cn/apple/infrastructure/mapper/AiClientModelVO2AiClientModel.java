package cn.apple.infrastructure.mapper;

import cn.apple.domain.agent.model.valobj.AiClientModelVO;
import cn.apple.infrastructure.persistent.po.AiClientModel;
import org.mapstruct.Mapper;

/**
 * @author _apple_sheep_
 * @description 模型配置，值对象
 * @date 2025/12/10
 * @Copyright github:Yuiang777
 */

@Mapper
public interface AiClientModelVO2AiClientModel {

    AiClientModelVO toVO(AiClientModel aiClientModel);

    AiClientModel toPO(AiClientModelVO aiClientModelVO);
}
