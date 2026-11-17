package cn.apple.infrastructure.mapper;

import cn.apple.domain.agent.model.valobj.AiClientAdvisorVO;
import cn.apple.infrastructure.persistent.po.AiClientAdvisor;
import org.mapstruct.Mapper;

/**
 * @author _apple_sheep_
 * @description
 * @date 2025/12/10
 * @Copyright github:Yuiang777
 */
@Mapper(componentModel = "spring")
public interface AiClientAdvisorVO2AiClientAdvisor {

    AiClientAdvisorVO toVO(AiClientAdvisor aiClientAdvisor);
    AiClientAdvisor toPO(AiClientAdvisorVO aiClientAdvisorVO);
}
