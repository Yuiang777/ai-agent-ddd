package cn.apple.domain.workflow.model;

public record RiskItem(String title,
                       RiskLevel level,
                       String description,
                       String suggestion) {
}

