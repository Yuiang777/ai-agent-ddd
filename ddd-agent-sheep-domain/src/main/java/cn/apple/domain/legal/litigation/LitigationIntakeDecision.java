package cn.apple.domain.legal.litigation;

import java.util.List;
import java.util.Map;

public record LitigationIntakeDecision(boolean litigation,
                                       boolean approved,
                                       boolean prohibitedConduct,
                                       List<String> reasons,
                                       Map<String, Object> normalized) {
}
