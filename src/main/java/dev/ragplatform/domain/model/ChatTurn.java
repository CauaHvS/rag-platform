package dev.ragplatform.domain.model;

import java.time.Instant;
import java.util.UUID;

public record ChatTurn(UUID id, UUID ownerId, String question, String answer, Instant createdAt) {}
