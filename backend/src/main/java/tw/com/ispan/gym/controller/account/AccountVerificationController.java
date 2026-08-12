package tw.com.ispan.gym.controller.account;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tw.com.ispan.gym.service.member.MemberRegisterService;

@RestController
@RequestMapping("/api/accounts")
public class AccountVerificationController {

    private final MemberRegisterService registerService;

    public AccountVerificationController(MemberRegisterService registerService) {
        this.registerService = registerService;
    }

    @GetMapping("/verify-email")
    public void verifyEmail(@RequestParam UUID token) {
        registerService.verifyEmail(token);
    }
}
