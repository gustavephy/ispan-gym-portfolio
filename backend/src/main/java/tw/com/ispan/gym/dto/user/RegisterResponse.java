package tw.com.ispan.gym.dto.user;

import java.time.LocalDateTime;

import tw.com.ispan.gym.entity.User;

public record RegisterResponse(
        Integer id,
        String firstName,
        String lastName,
        String email,
        String phone,
        String role,
        String status,
        LocalDateTime createdAt
) {
    public static RegisterResponse from(User user) {
        return new RegisterResponse(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole() != null ? user.getRole().name() : null,
                user.getStatus() != null ? user.getStatus().name() : null,
                user.getCreatedAt()
        );
    }
}