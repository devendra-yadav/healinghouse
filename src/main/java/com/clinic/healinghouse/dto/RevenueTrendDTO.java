package com.clinic.healinghouse.dto;

import java.math.BigDecimal;
import java.util.List;

/** Day-by-day revenue series for the dashboard's Chart.js line chart. */
public record RevenueTrendDTO(
        List<String> labels,
        List<BigDecimal> values
) {
}
