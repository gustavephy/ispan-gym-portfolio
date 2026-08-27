package tw.com.ispan.gym.controller.course;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tw.com.ispan.gym.dto.course.PrivateCourseResponse;
import tw.com.ispan.gym.dto.common.PageResponse;
import tw.com.ispan.gym.service.course.PrivateCourseService;

/**
 * ADMIN 私教課下架／重新上架。目前僅此三個端點，不是完整的 PrivateCourse 後台管理
 * （建立／編輯／瀏覽仍未串接，見 docs/API-REFERENCE.md §15.1 已知缺口）——新增這幾個
 * 端點的直接原因是 Trainer 停用前會檢查「名下是否還有生效中的私教課」，在此之前
 * 完全沒有辦法把已存在的 PrivateCourse 設回 INACTIVE，等於停用流程被卡死
 * （code review 發現，2026-08-19，見 trainer_management_next_phase 規格基準）。
 *
 * <p>受 SecurityConfig 既有的 /api/admin/** → hasRole("ADMIN") 規則保護，
 * 這裡額外疊上 @PreAuthorize，比照 AdminGroupCourseController 既有風格（雙層防護）。
 */
@RestController
@RequestMapping("/api/admin/private-courses")
@RequiredArgsConstructor
public class AdminPrivateCourseController {

    private final PrivateCourseService privateCourseService;

    /** 後台管理：分頁查詢所有私教課商品 */
    @GetMapping("/paged")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PageResponse<PrivateCourseResponse>> getPagedPrivateCourses(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer categoryId,
            @RequestParam(required = false) Integer trainerId,
            @RequestParam(required = false) String search) {
        PageResponse<PrivateCourseResponse> courses = privateCourseService.searchPrivateCoursesPaged(
                page, size, null, categoryId, trainerId, search);
        return ResponseEntity.ok(courses);
    }

    /** 查詢某教練名下所有私教課商品（不分狀態），供停用前確認要下架哪些商品用 */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<PrivateCourseResponse> getByTrainer(@RequestParam Integer trainerId) {
        return privateCourseService.getCoursesByTrainerForAdmin(trainerId);
    }

    /** 下架，成功回 200（更新既有資源的狀態，不是新建立），冪等。 */
    @PostMapping("/{privateCourseId}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateCourseResponse deactivate(@PathVariable Integer privateCourseId) {
        return privateCourseService.deactivateByAdmin(privateCourseId);
    }

    /** 重新上架，成功回 200，冪等。教練 UNAVAILABLE／已軟刪除時 409（§9 跨模組防護）。 */
    @PostMapping("/{privateCourseId}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivateCourseResponse activate(@PathVariable Integer privateCourseId) {
        return privateCourseService.activateByAdmin(privateCourseId);
    }
}
