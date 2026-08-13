package tw.com.ispan.gym.controller.aihealthchat;

import tw.com.ispan.gym.dto.aiplan.AiHealthPlanResponse;
import tw.com.ispan.gym.dto.aiplan.GeneratePlanRequest;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.aihealthchat.AiAdvisorService;
import jakarta.validation.Valid;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai-plans")
public class AiPlanController {
    private final AiAdvisorService aiAdvisorService;
    private final CurrentUserProvider currentUser;

    public AiPlanController(AiAdvisorService aiAdvisorService, CurrentUserProvider currentUser) {
        this.aiAdvisorService = aiAdvisorService; this.currentUser = currentUser;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CompletableFuture<AiHealthPlanResponse> generate(@Valid @RequestBody GeneratePlanRequest request) {
        return aiAdvisorService.generatePlan(currentUser.requireCurrentMemberId(), request);
    }

    @GetMapping
    public List<AiHealthPlanResponse> getHistory() {
        return aiAdvisorService.getHistory(currentUser.requireCurrentMemberId());
    }
}
