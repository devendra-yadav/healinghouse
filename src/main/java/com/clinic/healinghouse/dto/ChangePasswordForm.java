package com.clinic.healinghouse.dto;

import lombok.Data;

@Data
public class ChangePasswordForm {
    private String currentPassword;
    private String newPassword;
    private String confirmNewPassword;
}
