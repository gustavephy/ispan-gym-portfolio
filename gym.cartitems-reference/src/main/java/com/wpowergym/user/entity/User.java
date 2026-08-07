package com.wpowergym.user.entity;

import com.wpowergym.common.persistence.TaipeiTimestampEntity;
import jakarta.persistence.*;
import java.time.LocalDate;
import org.hibernate.annotations.Nationalized;

@Entity
@Table(name = "users")
public class User extends TaipeiTimestampEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Nationalized
    @Column(name = "first_name", nullable = false, length = 50)
    private String firstName;
    @Nationalized
    @Column(name = "last_name", nullable = false, length = 50)
    private String lastName;
    @Column(name = "dob")
    private LocalDate dob;
    @Column(name = "email", nullable = false, length = 100)
    private String email;
    @Column(name = "phone", length = 20)
    private String phone;
    @Enumerated(EnumType.STRING)
    @Column(name = "gender", length = 10)
    private Gender gender;
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role;
    @Column(name = "pwd_hash", length = 255)
    private String passwordHash;
    @Enumerated(EnumType.STRING)
    @Column(name = "oauth_provider", length = 20)
    private OAuthProvider oauthProvider;
    @Column(name = "oauth_sub", length = 255)
    private String oauthSub;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

    protected User() {}
    public Long getId() { return id; }
    public UserStatus getStatus() { return status; }
}
