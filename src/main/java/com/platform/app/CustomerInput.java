package com.platform.app;

import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.br.CPF;

public record CustomerInput(
    @NotBlank @Size(max = 150) String name,
    @NotBlank @Pattern(regexp = "[0-9]{11}") @CPF String cpf,
    @NotBlank @Email @Size(max = 254) String email,
    @NotNull CustomerStatus status) {}
