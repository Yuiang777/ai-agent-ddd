package cn.apple.domain.agent.model.entity;

import cn.apple.domain.agent.model.valobj.AiAgentEnumVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author _apple_sheep_
 * @description
 * @date 2025/12/10
 * @Copyright github:Yuiang777
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArmoryCommandEntity {


    // 命令类型
    private String commandType;


    // 命令ID
    private List<String> commandIdList;

    public String getLordDataStrategy() {
        return AiAgentEnumVO.getByCode(commandType).getLoadDataStrategy();
    }
}
