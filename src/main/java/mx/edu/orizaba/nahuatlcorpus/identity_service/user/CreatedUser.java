package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

import java.util.UUID;

public record CreatedUser(UUID id, String fullname, String email) {}
