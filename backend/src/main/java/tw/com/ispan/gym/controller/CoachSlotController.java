package tw.com.ispan.gym.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tw.com.ispan.gym.dto.coachslot.CoachSlotResponse;
import tw.com.ispan.gym.dto.coachslot.CreateCoachSlotRequest;
import tw.com.ispan.gym.security.CurrentUserProvider;
import tw.com.ispan.gym.service.CoachSlotService;
import org.springframework.web.bind.annotation.GetMapping;

@RestController
@RequestMapping("/api/trainer")
@RequiredArgsConstructor
public class CoachSlotController {

    private final CoachSlotService coachSlotService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping("/schedules")
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<CoachSlotResponse> createSlot(@Valid @RequestBody CreateCoachSlotRequest request) {
        Integer trainerId = currentUserProvider.requireCurrentUserId();
        CoachSlotResponse resp = coachSlotService.createSlot(trainerId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @DeleteMapping("/schedules/{slotId}")
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<CoachSlotResponse> cancelSlot(@PathVariable("slotId") Integer slotId) {
        Integer trainerId = currentUserProvider.requireCurrentUserId();
        CoachSlotResponse resp = coachSlotService.cancelSlot(trainerId, slotId);

        return ResponseEntity.status(HttpStatus.OK).body(resp);
    }

    @GetMapping("/schedules")
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<List<CoachSlotResponse>> getMySlots() {
        Integer trainerId = currentUserProvider.requireCurrentUserId();
        List<CoachSlotResponse> slots = coachSlotService.getMySlots(trainerId);

        return ResponseEntity.status(HttpStatus.OK).body(slots);
    }

}