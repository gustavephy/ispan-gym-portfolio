package tw.com.ispan.gym.service.member;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tw.com.ispan.gym.dto.account.VerifyEmailResponse;
import tw.com.ispan.gym.dto.member.MemberRegisterRequest;
import tw.com.ispan.gym.dto.member.MemberRegisterResponse;
import tw.com.ispan.gym.entity.account.Account;
import tw.com.ispan.gym.entity.account.EmailVerification;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.enums.account.AccountType;
import tw.com.ispan.gym.enums.account.VerificationPurpose;
import tw.com.ispan.gym.enums.member.UserStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.account.AccountRepository;
import tw.com.ispan.gym.repository.account.EmailVerificationRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.service.account.MailService;

@Service
@Transactional(readOnly = true)
public class MemberRegisterService {

    private static final Logger log = LoggerFactory.getLogger(MemberRegisterService.class);

    private final AccountRepository accounts;
    private final MemberRepository members;
    private final EmailVerificationRepository emailVerifications;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final long verificationTokenTtlMinutes;
    private final long resendCooldownMinutes;

    public MemberRegisterService(
            AccountRepository accounts,
            MemberRepository members,
            EmailVerificationRepository emailVerifications,
            PasswordEncoder passwordEncoder,
            MailService mailService,
            @Value("${app.mail.verification-token-ttl-minutes}") long verificationTokenTtlMinutes,
            @Value("${app.mail.verification-resend-cooldown-minutes}") long resendCooldownMinutes) {
        this.accounts = accounts;
        this.members = members;
        this.emailVerifications = emailVerifications;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.verificationTokenTtlMinutes = verificationTokenTtlMinutes;
        this.resendCooldownMinutes = resendCooldownMinutes;
    }

    @Transactional
    public MemberRegisterResponse register(MemberRegisterRequest request) {
        String email = normalizeEmail(request.getEmail());
        Account account = accounts.findByEmailIgnoreCase(email).orElse(null);

        if (account == null) {
            createAccountAndMember(request, email);
            return MemberRegisterResponse.of(email, "註冊成功，請查收驗證信完成帳號啟用。");
        }

        // 帳號已存在但沒有 Member 身分（例如純員工帳號）：匿名表單不能順手幫它加身分，
        // 訊息刻意不透露對方實際擁有哪種身分，只說「已有帳號」
        Member member = members.findByAccountId(account.getId()).orElse(null);
        if (member == null) {
            throw new BusinessRuleException("此 Email 已有帳號，請改用登入。");
        }

        // SUSPENDED 是硬性關卡，不論有沒有被軟刪除，都不可能透過任何自助流程繞過
        if (member.getStatus() == UserStatus.SUSPENDED) {
            throw new BusinessRuleException("此 Email 已被註冊");
        }

        boolean isDeleted = account.getDeletedAt() != null || member.getDeletedAt() != null;
        if (isDeleted) {
            // 重新啟用：這裡只重寄驗證信，不動密碼／個人資料——
            // 要等使用者真的點了信裡的連結、證明自己還控制這個信箱，才會真正復活帳號
            lockAndEnsureCooldownElapsed(account.getId());
            issueVerificationToken(account, VerificationPurpose.REACTIVATION);
            return MemberRegisterResponse.of(email, "此帳號已停用，我們已寄出重新啟用的驗證信，請查收。");
        }

        if (member.getStatus() == UserStatus.PENDING) {
            // 尚未驗證就重新送出註冊表單：只重寄驗證信，絕不覆蓋密碼／個人資料——
            // 否則等於讓任何人都能拿別人還沒驗證的 email 搶先把密碼換成自己的
            lockAndEnsureCooldownElapsed(account.getId());
            issueVerificationToken(account, VerificationPurpose.REGISTRATION);
            return MemberRegisterResponse.of(email, "此帳號尚待驗證，我們已重新寄出驗證信，請查收。");
        }

        throw new BusinessRuleException("此 Email 已被註冊");
    }

    /**
     * 註冊表單即時可用性檢查——刻意跟 {@link #register} 走完全相同的判斷分支，
     * 只是唯讀、不寫入、不寄信、不拋例外，直接回傳布林值。
     *
     * <p>「可用」不等於「查無這個帳號」：PENDING 未驗證、軟刪除都會讓 register() 實際成功
     * （分別觸發重寄驗證信／重新啟用信），所以這裡也回傳 true；只有帳號已被其他身分占用、
     * 已是完整 ACTIVE 會員、或已被停權時才回傳 false，這幾種情況 register() 真的會擋下來。
     *
     * <p>刻意不比照 register()／resendVerification() 檢查 60 秒重寄冷卻——這裡是給使用者
     * 打字當下的即時提示用，不是最終權威判斷，真正送出表單時 register() 仍會重新完整判斷一次；
     * 極少數「即時檢查顯示可用，但真正送出時剛好卡在冷卻窗口內」的情況，可接受的誤差。
     */
    public boolean isEmailAvailableForRegistration(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        Account account = accounts.findByEmailIgnoreCase(email).orElse(null);
        if (account == null) {
            return true;
        }

        Member member = members.findByAccountId(account.getId()).orElse(null);
        if (member == null) {
            return false;
        }
        if (member.getStatus() == UserStatus.SUSPENDED) {
            return false;
        }

        boolean isDeleted = account.getDeletedAt() != null || member.getDeletedAt() != null;
        if (isDeleted) {
            return true;
        }
        return member.getStatus() == UserStatus.PENDING;
    }

    /** 匿名重寄驗證信；不論該 email 是否存在、是否有東西可寄，一律回傳一樣的結果，避免被拿來枚舉帳號 */
    @Transactional
    public void resendVerification(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        Account account = accounts.findByEmailIgnoreCase(email).orElse(null);
        if (account == null) {
            return;
        }

        Member member = members.findByAccountId(account.getId()).orElse(null);
        if (member == null || member.getStatus() == UserStatus.SUSPENDED) {
            return;
        }

        VerificationPurpose purpose;
        if (account.getDeletedAt() != null || member.getDeletedAt() != null) {
            purpose = VerificationPurpose.REACTIVATION;
        } else if (member.getStatus() == UserStatus.PENDING) {
            purpose = VerificationPurpose.REGISTRATION;
        } else {
            return; // 已經是 ACTIVE，沒有東西需要重寄
        }

        // 用悲觀鎖鎖住同一個 account，避免併發的重寄請求在彼此都提交前一起通過冷卻檢查
        accounts.findByIdForUpdate(account.getId());
        if (isWithinResendCooldown(account.getId())) {
            return;
        }

        issueVerificationToken(account, purpose);
    }

    @Transactional
    public VerifyEmailResponse verifyEmail(UUID token) {
        EmailVerification verification = emailVerifications.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("驗證連結無效"));

        // 這條端點只啟用會員身分；員工驗證信不得在此換得會員資格
        if (verification.getTargetRole() != AccountType.MEMBER) {
            throw new BusinessRuleException("此驗證連結不適用於會員帳號");
        }

        VerificationPurpose purpose = verification.getPurpose();
        if (purpose == null) {
            // 這個欄位是後補的，理論上只有遷移前就存在的舊資料才可能是 null
            // （Hibernate ddl-auto=update 幫既有資料表加欄位時一律先建成可為 NULL）；
            // 放在「重複點擊」判斷之前，連已驗證過的舊資料也一併擋下，不讓 null 流到下面的 switch
            throw new BusinessRuleException("驗證連結已失效，請重新註冊或申請重寄驗證信");
        }

        // 提前取出 account——「重複點擊視為成功」的提前 return 跟正常流程都要回傳 email，
        // 兩個 return 點共用同一次取值，不用各自重複呼叫
        Account account = verification.getAccount();

        // 重複點擊視為成功，不報錯、也不重新觸發任何狀態變更
        if (verification.getVerifiedAt() != null) {
            return new VerifyEmailResponse(purpose.name(), account.getEmail());
        }

        if (verification.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessRuleException("驗證連結已過期，請重新註冊或申請重寄驗證信");
        }

        Member member = members.findByAccountId(account.getId())
                .orElseThrow(() -> new ResourceNotFoundException("找不到對應的會員資料"));

        // purpose 決定這把 key 原本要開哪道門；當下的狀態決定這道門現在還開不開得了，兩者缺一不可
        if (member.getStatus() == UserStatus.SUSPENDED) {
            throw new BusinessRuleException("此帳號已被停權，請聯繫客服");
        }

        switch (purpose) {
            case REGISTRATION -> {
                if (member.getStatus() != UserStatus.PENDING
                        || member.getDeletedAt() != null
                        || account.getDeletedAt() != null) {
                    throw new BusinessRuleException("此驗證連結已失效");
                }
                member.setStatus(UserStatus.ACTIVE);
            }
            case REACTIVATION -> {
                if (member.getDeletedAt() == null && account.getDeletedAt() == null) {
                    throw new BusinessRuleException("此驗證連結已失效");
                }
                member.restore();
                if (account.getDeletedAt() != null) {
                    account.restore();
                }
                // 不論剛剛是 account 還是只有 member 被刪除，恢復成功後都固定確保 MEMBER 角色在——
                // Set 不會重複新增，無條件執行比只在 account 曾被刪除時才補更保險
                account.getRoles().add(AccountType.MEMBER);
                member.setStatus(UserStatus.ACTIVE);
            }
        }

        verification.setVerifiedAt(LocalDateTime.now());
        return new VerifyEmailResponse(purpose.name(), account.getEmail());
    }

    private void createAccountAndMember(MemberRegisterRequest request, String email) {
        Account account = new Account();
        account.setEmail(email);
        account.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        account.getRoles().add(AccountType.MEMBER);
        account = accounts.save(account);

        Member member = new Member();
        member.setAccount(account);
        member.setFirstName(request.getFirstName().trim());
        member.setLastName(request.getLastName().trim());
        member.setPhone(request.getPhone().trim());
        member.setDob(request.getDob());
        member.setGender(request.getGender());
        member.setStatus(UserStatus.PENDING);
        members.save(member);

        issueVerificationToken(account, VerificationPurpose.REGISTRATION);
    }

    /** 新註冊／重新啟用／PENDING 重送共用：作廢同帳號同身分尚未驗證的舊 token，再發一封新的 */
    private void issueVerificationToken(Account account, VerificationPurpose purpose) {
        emailVerifications.deleteByAccountIdAndTargetRoleAndVerifiedAtIsNull(account.getId(), AccountType.MEMBER);

        UUID token = UUID.randomUUID();
        EmailVerification verification = new EmailVerification();
        verification.setAccount(account);
        verification.setTargetRole(AccountType.MEMBER);
        verification.setPurpose(purpose);
        verification.setToken(token);
        verification.setExpiresAt(LocalDateTime.now().plusMinutes(verificationTokenTtlMinutes));
        emailVerifications.save(verification);

        // 寄信失敗不應中斷流程——帳號與驗證 token 已經寫入，使用者仍可透過重寄驗證信或
        // 直接查 token 完成驗證；本機未設定 MAIL_USERNAME/PASSWORD 時預期會走到這裡。
        try {
            mailService.sendVerificationEmail(account.getEmail(), token, verificationTokenTtlMinutes, purpose);
        } catch (Exception ex) {
            log.warn("驗證信寄送失敗，account={}", account.getId(), ex);
        }
    }

    /**
     * 重寄/重新啟用發信前的併發防護：先用悲觀鎖鎖住這個 account，
     * 讓冷卻檢查與發新信變成同一個原子操作，避免兩個同時送出的請求
     * 都讀到「還沒過冷卻」之前的狀態、一起通過檢查。
     */
    private void lockAndEnsureCooldownElapsed(Integer accountId) {
        accounts.findByIdForUpdate(accountId);
        if (isWithinResendCooldown(accountId)) {
            throw new BusinessRuleException("請稍後再試，前一封驗證信仍在有效範圍內");
        }
    }

    private boolean isWithinResendCooldown(Integer accountId) {
        return emailVerifications
                .findTopByAccountIdAndTargetRoleAndVerifiedAtIsNullOrderByCreatedAtDesc(accountId, AccountType.MEMBER)
                .map(v -> v.getCreatedAt().isAfter(LocalDateTime.now().minusMinutes(resendCooldownMinutes)))
                .orElse(false);
    }

    private String normalizeEmail(String rawEmail) {
        return rawEmail.trim().toLowerCase(Locale.ROOT);
    }
}
