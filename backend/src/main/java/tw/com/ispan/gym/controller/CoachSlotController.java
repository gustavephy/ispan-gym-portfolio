package tw.com.ispan.gym.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.coachslot.CoachSlotResponse;
import tw.com.ispan.gym.dto.coachslot.CreateCoachSlotRequest;
import tw.com.ispan.gym.repository.UserRepository;
import tw.com.ispan.gym.service.CoachSlotService;

@RestController
@RequestMapping("/api/trainer")
@RequiredArgsConstructor
public class CoachSlotController {
    private final CoachSlotService coachSlotService;
    private final UserRepository userRepository;

    private Integer getCurrentUserId(UserDetails userDetails) {
        return userRepository.findByEmail(userDetails.getUsername()).orElseThrow().getId();
    }

    @PostMapping("/schedules")
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<CoachSlotResponse> createSlot(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody CreateCoachSlotRequest request) {
        Integer trainerId = getCurrentUserId(userDetails);
        CoachSlotResponse resp = coachSlotService.createSlot(trainerId, request);

        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

}
