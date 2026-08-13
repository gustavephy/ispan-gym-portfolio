package tw.com.ispan.gym.service.account;

import tw.com.ispan.gym.entity.account.Account;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class MailService {

    private final JavaMailSender mailSender;
    private final String verificationBaseUrl;

    public MailService(
            JavaMailSender mailSender,
            @Value("${app.mail.verification-base-url}") String verificationBaseUrl) {
        this.mailSender = mailSender;
        this.verificationBaseUrl = verificationBaseUrl;
    }

    public void sendVerificationEmail(String to, UUID token) {
        String link = verificationBaseUrl + "?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("請完成您的帳號註冊驗證");
        message.setText("感謝您註冊，請點擊以下連結完成驗證（24 小時內有效）：\n" + link);
        mailSender.send(message);
    }
}
