package tw.com.ispan.gym.controller.aihealthchat;

import tw.com.ispan.gym.dto.aiplan.AiHealthPlanResponse;
import tw.com.ispan.gym.dto.aiplan.GeneratePlanRequest;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.aihealthchat.AiAdvisorService;
import tw.com.ispan.gym.service.aihealthchat.PdfReportService;
import jakarta.validation.Valid;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai-plans")
public class AiPlanController {
    private final AiAdvisorService aiAdvisorService;
    private final PdfReportService pdfReportService;
    private final CurrentUserProvider currentUser;

    public AiPlanController(AiAdvisorService aiAdvisorService, PdfReportService pdfReportService,
            CurrentUserProvider currentUser) {
        this.aiAdvisorService = aiAdvisorService;
        this.pdfReportService = pdfReportService;
        this.currentUser = currentUser;
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

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable Integer id) {
        byte[] pdf = pdfReportService.generatePlanPdf(currentUser.requireCurrentMemberId(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ai-health-plan-" + id + ".pdf\"")
                .body(pdf);
    }
}
