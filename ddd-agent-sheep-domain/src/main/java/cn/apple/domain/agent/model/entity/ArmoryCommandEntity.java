package cn.apple.domain.agent.model.entity;

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

    private String commandType;

    private List<String> commandIdList;
}
