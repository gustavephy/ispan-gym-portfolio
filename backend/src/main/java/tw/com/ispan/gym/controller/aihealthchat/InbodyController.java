package tw.com.ispan.gym.controller.aihealthchat;

import tw.com.ispan.gym.dto.aihealthchat.InbodyRecordRequest;
import tw.com.ispan.gym.dto.aihealthchat.InbodyRecordResponse;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.aihealthchat.InbodyService;
import jakarta.validation.Valid;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inbody")
public class InbodyController {
    private final InbodyService inbodyService;
    private final CurrentUserProvider currentUser;

    public InbodyController(InbodyService inbodyService, CurrentUserProvider currentUser) {
        this.inbodyService = inbodyService; this.currentUser = currentUser;
    }

    @GetMapping
    public List<InbodyRecordResponse> getHistory() {
        return inbodyService.getHistory(currentUser.requireCurrentMemberId());
    }

    @GetMapping("/{id}")
    public InbodyRecordResponse getOne(@PathVariable Integer id) {
        return inbodyService.getOne(currentUser.requireCurrentMemberId(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InbodyRecordResponse create(@Valid @RequestBody InbodyRecordRequest request) {
        return inbodyService.create(currentUser.requireCurrentMemberId(), request);
    }

    @PutMapping("/{id}")
    public InbodyRecordResponse update(@PathVariable Integer id, @Valid @RequestBody InbodyRecordRequest request) {
        return inbodyService.update(currentUser.requireCurrentMemberId(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Integer id) {
        inbodyService.softDelete(currentUser.requireCurrentMemberId(), id);
    }
}
