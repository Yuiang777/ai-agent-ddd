package cn.apple.domain.legal.service;

import cn.apple.domain.legal.model.valobj.LegalScopeRoute;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LegalScopeRouter {

    private static final List<ScopeRule> RULES = List.of(
            new ScopeRule(
                    "property_management",
                    "物业服务与业主权益",
                    List.of("物业", "业主", "小区", "物业费", "维修基金", "公共收益", "业委会", "业主委员会", "车位", "建筑物区分所有权", "公共区域"),
                    List.of("民法典", "物业管理条例", "物业服务纠纷"),
                    List.of("物业服务", "业主", "建筑物区分所有权", "物业费", "维修基金", "公共收益", "民法典", "物业管理条例"),
                    0.92
            ),
            new ScopeRule(
                    "labor",
                    "劳动用工",
                    List.of("劳动", "劳动合同", "工资", "社保", "竞业", "解除", "离职", "加班", "工伤", "试用期", "经济补偿"),
                    List.of("劳动合同法", "劳动法", "社会保险法"),
                    List.of("劳动合同", "劳动关系", "工资", "社保", "经济补偿", "竞业限制", "劳动合同法"),
                    0.9
            ),
            new ScopeRule(
                    "civil_contract",
                    "民商事合同",
                    List.of("合同", "买卖", "租赁", "借款", "担保", "违约", "定金", "交付", "价款", "解除合同", "逾期"),
                    List.of("民法典", "买卖合同司法解释", "民间借贷司法解释"),
                    List.of("合同编", "违约责任", "合同解除", "履行", "交付", "民法典"),
                    0.82
            ),
            new ScopeRule(
                    "company",
                    "公司治理与股权",
                    List.of("公司", "股权", "股东", "董事", "监事", "分红", "出资", "章程", "法定代表人", "股权转让"),
                    List.of("公司法"),
                    List.of("公司治理", "股东权利", "出资义务", "股权转让", "公司法"),
                    0.86
            )
    );

    public LegalScopeRoute route(String query) {
        String text = query == null ? "" : query.trim();
        if (text.isEmpty()) {
            return LegalScopeRoute.general();
        }
        ScopeMatch best = null;
        for (ScopeRule rule : RULES) {
            ScopeMatch match = rule.match(text);
            if (match.score > 0 && (best == null || match.score > best.score)) {
                best = match;
            }
        }
        if (best == null) {
            return LegalScopeRoute.general();
        }
        double confidence = Math.min(0.98, best.rule.baseConfidence + Math.min(0.06, (best.score - 1) * 0.02));
        return new LegalScopeRoute(
                best.rule.scopeCode,
                best.rule.scopeName,
                best.rule.lawNameHints,
                best.rule.queryExpansionTerms,
                confidence,
                "命中关键词：" + String.join("、", best.matchedKeywords)
        );
    }

    private record ScopeRule(String scopeCode,
                             String scopeName,
                             List<String> keywords,
                             List<String> lawNameHints,
                             List<String> queryExpansionTerms,
                             double baseConfidence) {
        private ScopeMatch match(String text) {
            List<String> matched = keywords.stream().filter(text::contains).toList();
            return new ScopeMatch(this, matched.size(), matched);
        }
    }

    private record ScopeMatch(ScopeRule rule, int score, List<String> matchedKeywords) {
    }
}
