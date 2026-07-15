package dev.ragplatform.infrastructure.web.chat;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank @Size(max = 2000) String question,
        @Min(1) @Max(20) Integer k
) {
    public int kOrDefault() {
        return k != null ? k : 5;
    }
}
