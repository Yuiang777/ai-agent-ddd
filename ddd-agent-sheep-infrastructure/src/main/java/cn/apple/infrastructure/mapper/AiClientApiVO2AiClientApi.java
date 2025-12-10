package cn.apple.infrastructure.mapper;

import cn.apple.domain.agent.model.valobj.AiClientApiVO;
import cn.apple.infrastructure.persistent.po.AiClientApi;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * @author _apple_sheep_
 * @description 模型转换
 * @date 2025/12/10
 * @Copyright github:Yuiang777
 */
@Mapper
public interface AiClientApiVO2AiClientApi {

    AiClientApiVO toVO(AiClientApi aiClientApi);

    AiClientApi toPO(AiClientApiVO aiClientApiVO);
}
