package com.clinic.healinghouse.dto;

import java.math.BigDecimal;

public record ExpenseCategoryBreakdownDTO(String categoryName, BigDecimal amount) {
}
