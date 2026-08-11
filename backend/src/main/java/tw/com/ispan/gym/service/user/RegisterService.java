package tw.com.ispan.gym.service.user;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.common.exception.BusinessRuleException;
import tw.com.ispan.gym.common.exception.ResourceNotFoundException;
import tw.com.ispan.gym.dto.user.RegisterRequest;
import tw.com.ispan.gym.dto.user.RegisterResponse;
import tw.com.ispan.gym.entity.EmailVerification;
import tw.com.ispan.gym.entity.User;
import tw.com.ispan.gym.enums.UserStatus;
import tw.com.ispan.gym.repository.EmailVerificationRepository;
import tw.com.ispan.gym.repository.UserRepository;

@Service
@Transactional(readOnly = true)
public class RegisterService {

    private final UserRepository users;
    private final EmailVerificationRepository emailVerifications;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final long verificationTokenTtlHours;

    public RegisterService(
            UserRepository users,
            EmailVerificationRepository emailVerifications,
            PasswordEncoder passwordEncoder,
            MailService mailService,
            @Value("${app.mail.verification-token-ttl-hours}") long verificationTokenTtlHours) {
        this.users = users;
        this.emailVerifications = emailVerifications;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.verificationTokenTtlHours = verificationTokenTtlHours;
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String email = request.getEmail()
                .trim()
                .toLowerCase(Locale.ROOT);

        if (users.findByEmailIgnoreCase(email).isPresent()) {
            throw new BusinessRuleException("此 Email 已被註冊");
        }

        User user = new User();
        user.setFirstName(request.getFirstName().trim());
        user.setLastName(request.getLastName().trim());
        user.setEmail(email);
        user.setPhone(request.getPhone().trim());
        user.setDob(request.getDob());
        user.setGender(request.getGender());
        user.setPasswordHash(
                passwordEncoder.encode(request.getPassword())
        );
        user.setStatus(UserStatus.PENDING);

        User savedUser = users.save(user);

        UUID token = UUID.randomUUID();
        EmailVerification verification = new EmailVerification();
        verification.setUser(savedUser);
        verification.setToken(token);
        verification.setExpiresAt(LocalDateTime.now().plusHours(verificationTokenTtlHours));
        emailVerifications.save(verification);

        mailService.sendVerificationEmail(savedUser.getEmail(), token);

        return RegisterResponse.from(savedUser);
    }

    @Transactional
    public void verifyEmail(UUID token) {
        EmailVerification verification = emailVerifications.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("驗證連結無效"));

        if (verification.getVerifiedAt() != null) {
            return;
        }

        if (verification.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessRuleException("驗證連結已過期，請重新註冊或申請重寄驗證信");
        }

        verification.setVerifiedAt(LocalDateTime.now());

        User user = verification.getUser();
        user.setStatus(UserStatus.ACTIVE);
    }
}