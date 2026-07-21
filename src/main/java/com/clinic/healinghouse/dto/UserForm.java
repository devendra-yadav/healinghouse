package com.clinic.healinghouse.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Form-binding DTO for {@code /admin/users} create+edit (requirements/Security_RBAC_Requirements_v1.md §8.1).
 *  {@code role} is bound as a String (mirrors {@code WalletTopUpForm.paymentMethod}) to avoid an enum
 *  binding error on an unset select; {@code password} is only read on create — edit never touches the
 *  password, that's the dedicated reset-password action's job. */
@Data
public class UserForm {
    private Long id;
    @NotBlank
    private String username;
    private String fullName;
    @NotBlank
    private String role;
    private Long therapistId;
    private String password;
}
