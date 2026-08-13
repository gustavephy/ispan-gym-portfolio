package tw.com.ispan.gym.service.member;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.dto.member.MemberRegisterRequest;
import tw.com.ispan.gym.dto.member.MemberRegisterResponse;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.account.EmailVerification;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.account.AccountType;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.AccountRepository;
import tw.com.ispan.gym.repository.EmailVerificationRepository;
import tw.com.ispan.gym.repository.MemberRepository;
import tw.com.ispan.gym.service.account.MailService;

@Service
@Transactional(readOnly = true)
public class MemberRegisterService {

    private final AccountRepository accounts;
    private final MemberRepository members;
    private final EmailVerificationRepository emailVerifications;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final long verificationTokenTtlHours;

    public MemberRegisterService(
            AccountRepository accounts,
            MemberRepository members,
            EmailVerificationRepository emailVerifications,
            PasswordEncoder passwordEncoder,
            MailService mailService,
            @Value("${app.mail.verification-token-ttl-hours}") long verificationTokenTtlHours) {
        this.accounts = accounts;
        this.members = members;
        this.emailVerifications = emailVerifications;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.verificationTokenTtlHours = verificationTokenTtlHours;
    }

    @Transactional
    public MemberRegisterResponse register(MemberRegisterRequest request) {
        String email = request.getEmail()
                .trim()
                .toLowerCase(Locale.ROOT);

        Account account = accounts.findByEmailIgnoreCase(email).orElse(null);
        Member member;

        if (account == null) {
            account = new Account();
            account.setEmail(email);
            account.setPasswordHash(passwordEncoder.encode(request.getPassword()));
            account.getRoles().add(AccountType.MEMBER);
            account = accounts.save(account);

            member = new Member();
            member.setAccount(account);
        } else if (account.getDeletedAt() != null) {
            // 軟刪除的帳號重新註冊：復活原帳號並更新密碼
            account.setDeletedAt(null);
            account.setPasswordHash(passwordEncoder.encode(request.getPassword()));
            account.getRoles().add(AccountType.MEMBER);

            member = members.findByAccountId(account.getId()).orElseGet(Member::new);
            member.setAccount(account);
            member.setDeletedAt(null);
        } else {
            throw new BusinessRuleException("此 Email 已被註冊");
        }

        member.setFirstName(request.getFirstName().trim());
        member.setLastName(request.getLastName().trim());
        member.setPhone(request.getPhone().trim());
        member.setDob(request.getDob());
        member.setGender(request.getGender());
        member.setStatus(UserStatus.PENDING);
        Member savedMember = members.save(member);

        // 重新註冊（軟刪復活）時作廢先前未驗證的驗證信，確保同時只有一封有效連結
        emailVerifications.deleteByAccountIdAndTargetRoleAndVerifiedAtIsNull(account.getId(), AccountType.MEMBER);

        UUID token = UUID.randomUUID();
        EmailVerification verification = new EmailVerification();
        verification.setAccount(account);
        verification.setTargetRole(AccountType.MEMBER);
        verification.setToken(token);
        verification.setExpiresAt(LocalDateTime.now().plusHours(verificationTokenTtlHours));
        emailVerifications.save(verification);

        mailService.sendVerificationEmail(account.getEmail(), token);

        return MemberRegisterResponse.from(savedMember);
    }

    @Transactional
    public void verifyEmail(UUID token) {
        EmailVerification verification = emailVerifications.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("驗證連結無效"));

        // 這條端點只啟用會員身分；員工驗證信不得在此換得會員資格
        if (verification.getTargetRole() != AccountType.MEMBER) {
            throw new BusinessRuleException("此驗證連結不適用於會員帳號");
        }

        // 重複點擊驗證連結視為成功，不報錯
        if (verification.getVerifiedAt() != null) {
            return;
        }

        if (verification.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessRuleException("驗證連結已過期，請重新註冊或申請重寄驗證信");
        }

        verification.setVerifiedAt(LocalDateTime.now());

        Member member = members.findByAccountId(verification.getAccount().getId())
                .orElseThrow(() -> new ResourceNotFoundException("找不到對應的會員資料"));
        member.setStatus(UserStatus.ACTIVE);
    }
}
