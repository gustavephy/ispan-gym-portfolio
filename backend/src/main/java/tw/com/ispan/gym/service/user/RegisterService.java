package tw.com.ispan.gym.service.user;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.dto.user.RegisterRequest;
import tw.com.ispan.gym.dto.user.RegisterResponse;
import tw.com.ispan.gym.entity.EmailVerification;
import tw.com.ispan.gym.entity.Member;
import tw.com.ispan.gym.enums.UserStatus;
import tw.com.ispan.gym.repository.EmailVerificationRepository;
import tw.com.ispan.gym.repository.MemberRepository;

@Service
@Transactional(readOnly = true)
public class RegisterService {

    private final MemberRepository users;
    private final EmailVerificationRepository emailVerifications;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final long verificationTokenTtlHours;

    public RegisterService(
            MemberRepository users,
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

        Member user = new Member();
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

        Member savedUser = users.save(user);

        UUID token = UUID.randomUUID();
        EmailVerification verification = new EmailVerification();
        verification.setMember(savedUser);
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

        Member user = verification.getMember();
        user.setStatus(UserStatus.ACTIVE);
    }
}