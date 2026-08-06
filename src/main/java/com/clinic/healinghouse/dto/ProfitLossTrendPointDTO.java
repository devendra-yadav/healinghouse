package com.clinic.healinghouse.dto;

import java.math.BigDecimal;

public record ProfitLossTrendPointDTO(String periodLabel, BigDecimal revenue, BigDecimal expenses, BigDecimal profit) {
}
