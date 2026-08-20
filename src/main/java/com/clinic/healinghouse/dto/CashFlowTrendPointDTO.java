package com.clinic.healinghouse.dto;

import java.math.BigDecimal;

public record CashFlowTrendPointDTO(String periodLabel, BigDecimal inflow, BigDecimal outflow, BigDecimal net) {
}
