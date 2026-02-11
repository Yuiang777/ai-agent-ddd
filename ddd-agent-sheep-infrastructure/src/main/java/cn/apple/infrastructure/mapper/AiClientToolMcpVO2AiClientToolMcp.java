package cn.apple.infrastructure.mapper;

import cn.apple.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.apple.infrastructure.persistent.po.AiClientToolMcp;
import org.mapstruct.Mapper;

/**
 * @author _apple_sheep_
 * @description
 * @date 2025/12/10
 * @Copyright github:Yuiang777
 */
@Mapper(componentModel = "spring")
public interface AiClientToolMcpVO2AiClientToolMcp {

    AiClientToolMcpVO toVO(AiClientToolMcp aiClientToolMcp);
    AiClientToolMcp toPO(AiClientToolMcpVO aiClientToolMcpVO);
}
