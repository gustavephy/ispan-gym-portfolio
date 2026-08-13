package tw.com.ispan.gym.controller.member;

import tw.com.ispan.gym.entity.member.Member;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import tw.com.ispan.gym.dto.member.MemberRegisterRequest;
import tw.com.ispan.gym.dto.member.MemberRegisterResponse;
import tw.com.ispan.gym.service.member.MemberRegisterService;

@RestController
@RequestMapping("/api/members/register")
public class MemberRegisterController {

    private final MemberRegisterService registerService;

    public MemberRegisterController(MemberRegisterService registerService) {
        this.registerService = registerService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemberRegisterResponse register(@Valid @RequestBody MemberRegisterRequest request) {
        return registerService.register(request);
    }
}
