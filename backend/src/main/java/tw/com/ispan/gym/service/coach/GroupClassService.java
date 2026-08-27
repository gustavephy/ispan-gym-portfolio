package tw.com.ispan.gym.service.coach;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tw.com.ispan.gym.dto.coach.CreateGroupClassSessionRequest;
import tw.com.ispan.gym.dto.coach.GroupClassBookingResponse;
import tw.com.ispan.gym.dto.coach.GroupClassSessionResponse;
import tw.com.ispan.gym.dto.common.PageResponse;
import tw.com.ispan.gym.entity.coach.GroupClassBooking;
import tw.com.ispan.gym.entity.coach.GroupClassSession;
import tw.com.ispan.gym.entity.course.GroupCourse;
import tw.com.ispan.gym.enums.course.CourseStatus;
import tw.com.ispan.gym.entity.member.Member;
import tw.com.ispan.gym.entity.member.MemberSubscription;
import tw.com.ispan.gym.enums.coach.ClassRoom;
import tw.com.ispan.gym.enums.coach.GroupClassBookingStatus;
import tw.com.ispan.gym.enums.coach.GroupClassSessionStatus;
import tw.com.ispan.gym.enums.subscription.SubscriptionStatus;
import tw.com.ispan.gym.exception.BusinessRuleException;
import tw.com.ispan.gym.exception.ConfirmationRequiredException;
import tw.com.ispan.gym.exception.ForbiddenAccessException;
import tw.com.ispan.gym.exception.ResourceNotFoundException;
import tw.com.ispan.gym.repository.coach.GroupClassBookingRepository;
import tw.com.ispan.gym.repository.coach.GroupClassSessionRepository;
import tw.com.ispan.gym.repository.course.GroupCourseRepository;
import tw.com.ispan.gym.repository.member.MemberRepository;
import tw.com.ispan.gym.repository.member.MemberSubscriptionRepository;
import tw.com.ispan.gym.service.trainer.TrainerService;

@Service
@RequiredArgsConstructor
public class GroupClassService {

        /**
         * 團課場次商業邏輯：負責場次的建立、查詢、更新與會員報名。
         *
         * 注意：目前 getUpcomingSessions() 透過 entity 關聯存取 trainer/employee/課程名稱，
         * 會有 N+1 的風險（多筆場次時每筆都會觸發 lazy load）。建議改用 repository 的
         * fetch-join 或使用 projection DTO 的查詢來避免大量 SQL。
         *
         * 另外：本 Service 的回傳 DTO 含有 `version` 欄位，供前端實作樂觀鎖（update 時帶回）。
         */

        private final GroupClassSessionRepository groupClassSessionRepository;
        private final GroupCourseRepository groupCourseRepository;
        private final MemberSubscriptionRepository memberSubscriptionRepository;
        private final GroupClassBookingRepository groupClassBookingRepository;

        private final MemberRepository memberRepository;
        private final TrainerService trainerService;

        /**
         * 同一間教室，兩堂團課場次之間至少要間隔的清場緩衝時間（分鐘）。
         * 套用方式：檢查重疊時，把「新場次」的時間窗前後各撐大這個值再比對，
         * 而不是修改 findOverlappingByRoom 本身的查詢邏輯 —— 緩衝時間是業務規則，
         * 刻意留在 Service 層，之後要調整分鐘數只改這一個常數。
         */
        private static final int ROOM_CLEANUP_BUFFER_MINUTES = 10;

        /**
         * 教室關鍵字搜尋用：search 字串是否命中某教室的中文顯示名稱。
         * ClassRoom 是 enum，JPQL 對 enum 欄位套字串函式（LOWER/LIKE）並不保證能編譯，
         * 故在這裡先把 search 對照顯示名稱算成固定 boolean，查詢裡只做字面值相等比較。
         */
        private boolean matchesRoom(ClassRoom room, String search) {
                return search != null && !search.isBlank() && room.getDisplayName().contains(search);
        }

        @Transactional(readOnly = true)
        public List<GroupClassSessionResponse> getUpcomingSessions() {
                // 注意：此查詢會沿用 Lazy 關聯存取 trainer/employee/課程名稱，可能引發 N+1。
                // 建議改用 repository fetch-join 或 projection DTO 以減少 SQL 次數。
                // 使用 fetch-join 的 repository 方法一次撈出 course/trainer/employee，
                // 避免後續映射時觸發 N+1 查詢。
                List<GroupClassSession> upcomingSessions = groupClassSessionRepository
                                .findByStatusAndStartAtAfterWithCourseTrainerOrderByStartAtAsc(
                                                GroupClassSessionStatus.SCHEDULED, LocalDateTime.now());
                List<GroupClassSessionResponse> resp = upcomingSessions.stream()
                                .map(session -> new GroupClassSessionResponse(
                                                session.getId(),
                                                session.getCourse().getId(),
                                                session.getCourse().getTrainer().getId(),
                                                session.getCourse().getTrainer().getEmployee().getFirstName() + " "
                                                                + session.getCourse().getTrainer().getEmployee()
                                                                                .getLastName(),
                                                session.getCourse().getName(),
                                                session.getRoomName(),
                                                session.getRoomName().getDisplayName(),
                                                session.getRoomName().getColorHex(),
                                                session.getStartAt(),
                                                session.getEndAt(),
                                                session.getCapacity(),
                                                session.getBookedCount(),
                                                session.getRemainingSeats(),
                                                session.getBookedCount() >= session.getCapacity(),
                                                session.getStatus(),
                                                session.getVersion(),
                                                session.getCreatedAt(),
                                                session.getUpdatedAt()))
                                .toList();
                return resp;
        }

        /**
         * 後台團課場次列表，分頁＋依課程名稱／教練姓名／教室搜尋。篩選邏輯沿用
         * {@link #getUpcomingSessions()}（status=SCHEDULED 且尚未開始），只是多了分頁與搜尋，
         * 這是全新的方法，{@link #getUpcomingSessions()} 本身不受影響——那支是會員瀏覽團課行事曆的
         * 資料來源，前端依賴它一次回傳全部資料，不能改動。
         */
        @Transactional(readOnly = true)
        public PageResponse<GroupClassSessionResponse> getUpcomingSessionsForAdmin(int page, int size,
                        String search) {
                int safePage = Math.max(page, 0);
                int safeSize = Math.min(Math.max(size, 1), 100);
                Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.ASC, "startAt"));

                Page<GroupClassSession> result = groupClassSessionRepository.searchUpcomingSessions(
                                GroupClassSessionStatus.SCHEDULED, LocalDateTime.now(), search,
                                matchesRoom(ClassRoom.YOGA_ROOM, search), matchesRoom(ClassRoom.BOXING_ROOM, search),
                                matchesRoom(ClassRoom.WEIGHT_ROOM, search), pageable);

                List<GroupClassSessionResponse> items = result.getContent().stream()
                                .map(session -> new GroupClassSessionResponse(
                                                session.getId(),
                                                session.getCourse().getId(),
                                                session.getCourse().getTrainer().getId(),
                                                session.getCourse().getTrainer().getEmployee().getFirstName() + " "
                                                                + session.getCourse().getTrainer().getEmployee()
                                                                                .getLastName(),
                                                session.getCourse().getName(),
                                                session.getRoomName(),
                                                session.getRoomName().getDisplayName(),
                                                session.getRoomName().getColorHex(),
                                                session.getStartAt(),
                                                session.getEndAt(),
                                                session.getCapacity(),
                                                session.getBookedCount(),
                                                session.getRemainingSeats(),
                                                session.getBookedCount() >= session.getCapacity(),
                                                session.getStatus(),
                                                session.getVersion(),
                                                session.getCreatedAt(),
                                                session.getUpdatedAt()))
                                .toList();

                return new PageResponse<>(items, safePage, safeSize, result.getTotalElements(), result.getTotalPages());
        }

        /**
         * 管理層建立團課場次。操作者角色限定 ADMIN（由 Controller 的 @PreAuthorize 把關），
         * 不需要解析「本人」身分——GroupClassSession 沒有綁定建立者，
         * 教練是誰由 groupCourseId 對應的 GroupCourse 決定，不是這裡指定的。
         *
         * 檢查順序（依實際程式碼執行先後）：
         * 1. 課程不存在 → 404。
         * 2. 課程已下架（status != ACTIVE）→ 409，不可再用它建立新場次
         *    （既有場次不受影響，只擋新建這個動作）。
         * 3. 教練目前無法承接新業務（軟刪除／非在職／非可服務狀態）→ 409，
         *    見 {@link tw.com.ispan.gym.service.trainer.TrainerService#lockAndAssertAvailableForNewBusiness}
         *    （§9 跨模組防護，內含悲觀鎖，防止與教練停用流程的競態）。
         * 4. 同教室同時段已有其他（未取消）場次時間本身重疊 → 409（教室衝突，
         *    邏輯跟 CoachSlotService 的時段重疊檢查同一套，只是比對對象從教練換成教室）。
         * 5. 沒有真重疊，但與前後場次間隔不足 ROOM_CLEANUP_BUFFER_MINUTES 分鐘 → 409
         *    （教室清場緩衝時間不足）。
         * 全部通過才真的建立場次。
         *
         * capacity 允許不填，不填時 fallback 回該團課課程的 maxCapacity，
         * 讓管理層可以視實際教室調整（例如某天借到比較小的教室，容量就設低一點）。
         */
        @Transactional
        public GroupClassSessionResponse createSession(CreateGroupClassSessionRequest request) {
                Integer groupCourseId = request.getGroupCourseId();
                GroupCourse groupCourse = groupCourseRepository.findById(groupCourseId)
                                .orElseThrow(() -> new ResourceNotFoundException("查無此課程"));

                // 課程已下架就不能再用它建立新場次，跟前端下架確認對話框的文案對齊
                // （既有場次不受影響，這裡只擋「新建」這個動作）
                if (groupCourse.getStatus() != CourseStatus.ACTIVE) {
                        throw new BusinessRuleException("此課程已下架，無法建立新場次");
                }

                // §9 跨模組防護：教練若已 UNAVAILABLE 或軟刪除，不可再指派新的團課場次，
                // 不能只靠前端隱藏按鈕擋（見 trainer_management_next_phase 規格基準）。
                // GroupCourseService#createCourse／activateCourse 現在也有同一道檢查
                // （2026-08-19 code review 後補上，原規格§9 本來就把「建立或指派 GroupClass」
                // 跟這裡的「建立或指派 GroupClassSession」列成兩個獨立防護點）——這裡仍然要查，
                // 因為課程主檔 ACTIVE 不代表底下每一場次建立時教練仍可服務，兩層互相獨立。
                // 只取 .getId()（lazy proxy，不觸發初始化，見 TrainerService 的方法註解），
                // 真正的鎖定讀取交給 lockAndAssertAvailableForNewBusiness()，這樣才能跟
                // AdminTrainerService.suspend() 搶同一顆鎖，堵住競態。
                trainerService.lockAndAssertAvailableForNewBusiness(groupCourse.getTrainer().getId());

                // 先用「原始時間」查真重疊：兩堂課時間本身有交集
                List<GroupClassSession> trueOverlap = groupClassSessionRepository
                                .findOverlappingByRoom(request.getRoomName(), request.getStartAt(), request.getEndAt());
                if (!trueOverlap.isEmpty()) {
                        throw new BusinessRuleException("此教室已有重疊時段");
                }

                // 沒有真重疊，才用「加上清場緩衝」的時間再查一次：
                // 命中代表時間沒有真的對撞，但跟前後場次的間隔不足 ROOM_CLEANUP_BUFFER_MINUTES 分鐘
                List<GroupClassSession> tooClose = groupClassSessionRepository
                                .findOverlappingByRoom(request.getRoomName(),
                                                request.getStartAt().minusMinutes(ROOM_CLEANUP_BUFFER_MINUTES),
                                                request.getEndAt().plusMinutes(ROOM_CLEANUP_BUFFER_MINUTES));
                if (!tooClose.isEmpty()) {
                        throw new BusinessRuleException(
                                        "此教室與前後場次間隔不足 " + ROOM_CLEANUP_BUFFER_MINUTES + " 分鐘（需保留清場時間），請調整時段");
                }

                GroupClassSession groupClassSession = new GroupClassSession();
                groupClassSession.setCourse(groupCourse);
                groupClassSession.setRoomName(request.getRoomName());
                groupClassSession.setStartAt(request.getStartAt());
                groupClassSession.setEndAt(request.getEndAt());
                groupClassSession.setStatus(GroupClassSessionStatus.SCHEDULED);
                groupClassSession
                                .setCapacity(request.getCapacity() != null ? request.getCapacity()
                                                : groupCourse.getMaxCapacity());

                GroupClassSession saved = groupClassSessionRepository.save(groupClassSession);
                return new GroupClassSessionResponse(saved.getId(), groupCourse.getId(),
                                groupCourse.getTrainer().getId(),
                                groupCourse.getTrainer().getEmployee().getFirstName() + " "
                                                + groupCourse.getTrainer().getEmployee().getLastName(),
                                groupCourse.getName(), saved.getRoomName(), saved.getRoomName().getDisplayName(),
                                saved.getRoomName().getColorHex(), saved.getStartAt(), saved.getEndAt(),
                                saved.getCapacity(),
                                saved.getBookedCount(), saved.getRemainingSeats(),
                                saved.getBookedCount() >= saved.getCapacity(),
                                saved.getStatus(), saved.getVersion(), saved.getCreatedAt(), saved.getUpdatedAt());
        }

        /**
         * 更新場次基本資訊（教室 / 時間 / 容量）。
         *
         * 檢查順序（請勿變更）：
         * 1. 讀取場次，找不到回 {@link ResourceNotFoundException}。
         * 2. 已被取消（CANCELLED）不可修改，回 {@link BusinessRuleException}。
         * 3. 使用前端帶入的 {@code version} 做快速版本檢查，若不符回 {@link BusinessRuleException}。
         * 4. 檢查教室時間是否與其他場次重疊（修改時排除自己），若衝突回 {@link BusinessRuleException}。
         * 5. 若要修改容量，禁止將容量降到小於當前已報名人數（避免 remainingSeats 變負數），否則回
         * {@link BusinessRuleException}。
         *
         * 設計與注意事項：
         * - 此方法採「快速失敗」策略：先檢查常見錯誤情況並回明確錯誤訊息，讓前端能對應 409 / 404 等情況。
         * - {@code version} 檢查只是給前端友善提示；實際並發控制仍依賴 JPA 的 {@code @Version} 與資料庫的一致性保證。
         * - 重疊檢查目前採簡單的 repository 查詢再在程式中排除自己，若日後改為分頁或更複雜的查詢，
         * 建議改成 repository projection 或把重疊檢查下推為一個 SQL 條件以減少 race condition 風險。
         * - 使用 {@code saveAndFlush} 是刻意的：希望在方法返回前把變更立即 flush 至資料庫，使得後續流程（若有）能即時看到新版本。
         */
        @Transactional
        public GroupClassSessionResponse updateSession(Integer sessionId,
                        tw.com.ispan.gym.dto.coach.UpdateGroupClassSessionRequest request) {
                // 1) 取得場次，找不到拋 404
                GroupClassSession session = groupClassSessionRepository.findById(sessionId)
                                .orElseThrow(() -> new ResourceNotFoundException("查無此課程場次"));

                // 2) 已取消的場次不得修改（與 CoachSlotService.updateSlot 行為一致）
                if (session.getStatus() == GroupClassSessionStatus.CANCELLED) {
                        throw new BusinessRuleException("此場次已被取消，無法修改，請重新建立新場次");
                }

                // 3) 版本檢查（快速失敗）：前端應帶上最新的 version
                if (session.getVersion() == null || !session.getVersion().equals(request.getVersion())) {
                        throw new BusinessRuleException("此場次已被其他人修改，請重新載入最新資料");
                }

                // 4) 教室重疊檢查（排除自己）：若發現其他重疊場次，直接拒絕
                // 邏輯同 createSession()：先查真重疊，沒有才查緩衝範圍，exclude 自己的邏輯兩段都要做
                List<GroupClassSession> trueOverlap = groupClassSessionRepository
                                .findOverlappingByRoom(request.getRoomName(), request.getStartAt(), request.getEndAt());
                trueOverlap.removeIf(s -> s.getId().equals(sessionId));
                if (!trueOverlap.isEmpty()) {
                        throw new BusinessRuleException("此教室已有重疊時段");
                }

                List<GroupClassSession> tooClose = groupClassSessionRepository
                                .findOverlappingByRoom(request.getRoomName(),
                                                request.getStartAt().minusMinutes(ROOM_CLEANUP_BUFFER_MINUTES),
                                                request.getEndAt().plusMinutes(ROOM_CLEANUP_BUFFER_MINUTES));
                tooClose.removeIf(s -> s.getId().equals(sessionId));
                if (!tooClose.isEmpty()) {
                        throw new BusinessRuleException(
                                        "此教室與前後場次間隔不足 " + ROOM_CLEANUP_BUFFER_MINUTES + " 分鐘（需保留清場時間），請調整時段");
                }

                // 5) 更新可變欄位
                session.setRoomName(request.getRoomName());
                session.setStartAt(request.getStartAt());
                session.setEndAt(request.getEndAt());
                if (request.getCapacity() != null) {
                        // 容量不可小於目前已報名人數，否則 remainingSeats 會變負數
                        if (request.getCapacity() < session.getBookedCount()) {
                                throw new BusinessRuleException("容量不可小於目前已報名人數");
                        }
                        session.setCapacity(request.getCapacity());
                }

                // 儲存並立即 flush，確保回傳的 DTO 含有最新的 version 與 timestamps
                GroupClassSession saved = groupClassSessionRepository.saveAndFlush(session);
                return new GroupClassSessionResponse(saved.getId(), saved.getCourse().getId(),
                                saved.getCourse().getTrainer().getId(),
                                saved.getCourse().getTrainer().getEmployee().getFirstName() + " "
                                                + saved.getCourse().getTrainer().getEmployee().getLastName(),
                                saved.getCourse().getName(), saved.getRoomName(), saved.getRoomName().getDisplayName(),
                                saved.getRoomName().getColorHex(), saved.getStartAt(), saved.getEndAt(),
                                saved.getCapacity(),
                                saved.getBookedCount(), saved.getRemainingSeats(),
                                saved.getBookedCount() >= saved.getCapacity(),
                                saved.getStatus(), saved.getVersion(), saved.getCreatedAt(), saved.getUpdatedAt());
        }

        /**
         * 會員報名團課場次。資格判斷依據是「有效訂閱」，不是額度（團課不走購買流程）。
         * 檢查順序：查無場次 → 404；查無有效訂閱 → 409；已報名過此場次 → 409；
         * 額滿或場次已取消由 session.book() 自己判斷並丟例外，這裡不用重複寫。
         */
        /**
         * 會員報名場次。
         *
         * 流程說明：
         * 1. 取得場次，若不存在回 404（ResourceNotFoundException）。
         * 2. 檢查會員是否有「有效訂閱」（MemberSubscription），若無則拒絕報名。
         * 3. 檢查該會員是否已報名此場次（避免重複報名）。
         * 4. 呼叫 entity 的 `book()`（由 entity 負責額滿或場次取消的業務檢查並拋例外），
         * 再將變更存回資料庫，並建立 GroupClassBooking 紀錄。
         *
         * 注意事項：
         * - `book()` 與 `@Version` 一起協助避免超賣（樂觀鎖）；若多個使用者同時報名，
         * JPA 的樂觀鎖或資料庫的約束會阻止超額狀況並拋出例外，Controller 層應對此例外做友善回應。
         * - 此方法不處理付款/額度邏輯，團課報名以訂閱（Subscription）為資格依據。
         */
        @Transactional
        public GroupClassBookingResponse bookSession(Integer memberId, Integer sessionId) {
                // 1) 取得場次
                GroupClassSession groupClassSession = groupClassSessionRepository.findById(sessionId)
                                .orElseThrow(() -> new ResourceNotFoundException("查無此團課場次"));

                // 2) 檢查是否有有效訂閱（資格檢查）
                Optional<MemberSubscription> subscription = memberSubscriptionRepository
                                .findFirstByMemberIdAndEndDateAfterAndStatusOrderByEndDateDesc(memberId,
                                                LocalDateTime.now(), SubscriptionStatus.ACTIVE);
                if (subscription.isEmpty()) {
                        throw new BusinessRuleException("查無有效訂閱，請確認訂閱狀態");
                }

                // 3) 檢查是否已報名此場次（避免重複報名）
                Optional<GroupClassBooking> book = groupClassBookingRepository.findBySessionIdAndMemberIdAndStatus(
                                sessionId,
                                memberId, GroupClassBookingStatus.BOOKED);
                if (!book.isEmpty()) {
                        throw new BusinessRuleException("已報名此場次，請勿重複報名");
                }

                // 4) 取得會員實體（供建立 booking 用）
                Member member = memberRepository.findById(memberId)
                                .orElseThrow(() -> new ResourceNotFoundException("查無此會員"));

                // 5) 實際報名流程：呼叫 entity 層的 book() 以驗證額滿/狀態，然後存回 session
                groupClassSession.book();
                groupClassSessionRepository.save(groupClassSession);

                // 6) 建立 booking 紀錄
                GroupClassBooking groupClassBooking = new GroupClassBooking();
                groupClassBooking.setSession(groupClassSession);
                groupClassBooking.setMember(member);
                groupClassBooking.setStatus(GroupClassBookingStatus.BOOKED);

                GroupClassBooking saveBooking = groupClassBookingRepository.save(groupClassBooking);

                // 7) 回傳 booking response（包含場次基本資訊，供前端顯示）
                GroupClassBookingResponse resp = new GroupClassBookingResponse(saveBooking.getId(), sessionId,
                                groupClassSession.getCourse().getName(),
                                groupClassSession.getRoomName().getDisplayName(),
                                groupClassSession.getStartAt(), groupClassSession.getEndAt(), saveBooking.getStatus());
                return resp;
        }

        /**
         * 會員取消自己的團課報名。
         *
         * 商業規則檢查順序（不可變更）：
         * 1) 查無 booking → ResourceNotFoundException
         * 2) 非本人操作 → ForbiddenAccessException
         * 3) status 非 BOOKED → BusinessRuleException
         * 4) 場次已開始 → BusinessRuleException
         * 5) 成功則呼叫 session.cancelBooking(), 儲存 session 與 booking，回傳
         * GroupClassBookingResponse
         */
        @Transactional
        public GroupClassBookingResponse cancelBooking(Integer memberId, Integer bookingId) {
                GroupClassBooking booking = groupClassBookingRepository.findById(bookingId)
                                .orElseThrow(() -> new ResourceNotFoundException("查無此報名紀錄"));

                if (!booking.getMember().getId().equals(memberId)) {
                        throw new ForbiddenAccessException("無權操作此報名");
                }

                if (booking.getStatus() != GroupClassBookingStatus.BOOKED) {
                        throw new BusinessRuleException("此報名目前無法取消");
                }

                if (booking.getSession().getStartAt().isBefore(LocalDateTime.now())) {
                        throw new BusinessRuleException("場次已開始，無法取消");
                }

                // 執行取消：由 entity 處理 bookedCount 的遞減與檢查
                GroupClassSession session = booking.getSession();
                session.cancelBooking();
                groupClassSessionRepository.save(session);

                booking.setStatus(GroupClassBookingStatus.CANCELLED);
                GroupClassBooking saved = groupClassBookingRepository.save(booking);

                GroupClassBookingResponse resp = new GroupClassBookingResponse(saved.getId(), session.getId(),
                                session.getCourse().getName(), session.getRoomName().getDisplayName(),
                                session.getStartAt(),
                                session.getEndAt(),
                                saved.getStatus());

                return resp;
        }

        /**
         * 分頁查詢會員自己的所有團課報名紀錄，可依課程名稱或教室搜尋、依狀態與場次開始日期區間篩選，
         * 依場次開始時間由近到遠排序（Pageable 的 Sort，不寫在 JPQL 裡，理由同
         * {@code GroupCourseRepository#searchCourses}）。
         * size 做安全上限（1~100）、page 不可小於 0，理由同 {@code GroupCourseService#getAllCourses}。
         * dateFrom／dateTo 是使用者在畫面上選的「日期」（不含時間），dateTo 內部轉成隔天 00:00
         * 做上界（不含），讓 dateTo 當天整天都算在篩選範圍內。
         */
        @Transactional(readOnly = true)
        public PageResponse<GroupClassBookingResponse> getMyBookings(Integer memberId, int page, int size,
                        String search, GroupClassBookingStatus status, LocalDate dateFrom,
                        LocalDate dateTo) {
                // 先依狀態再依開課時間：BOOKED 排在 CANCELLED 之前（EnumType.STRING 存的是字面值，
                // 字母序剛好是 BOOKED < CANCELLED），讓「還有效的報名」永遠在最前面。
                // 少了狀態這一層，同一場次的已取消紀錄會跟剛報名的混在一起，
                // 使用者報名完往下看，第一眼看到的可能是舊的「已取消」，會以為報名失敗。
                return searchBookings(memberId, page, size, search, status, dateFrom, dateTo,
                                Sort.by(Sort.Order.asc("status"), Sort.Order.asc("session.startAt")));
        }

        /**
         * 管理者查詢指定會員的所有團課報名紀錄，篩選條件與分頁邏輯跟 {@link #getMyBookings} 完全相同，
         * 只有排序不同：純依開課時間排序，不把 BOOKED 排到 CANCELLED 前面。
         * {@code getMyBookings} 的狀態優先排序是為了「會員自己看」設計的（讓還有效的報名在前面、
         * 一眼認出報名有沒有成功），管理者是要核對紀錄而非確認自己剛做的操作，依時間排序更直覺，
         * 也才不會讓同一天的多筆紀錄被狀態拆得四散難以對照。
         */
        @Transactional(readOnly = true)
        public PageResponse<GroupClassBookingResponse> getMemberBookingsForAdmin(Integer memberId, int page, int size,
                        String search, GroupClassBookingStatus status, LocalDate dateFrom, LocalDate dateTo) {
                return searchBookings(memberId, page, size, search, status, dateFrom, dateTo,
                                Sort.by(Sort.Direction.ASC, "session.startAt"));
        }

        private PageResponse<GroupClassBookingResponse> searchBookings(Integer memberId, int page, int size,
                        String search, GroupClassBookingStatus status, LocalDate dateFrom, LocalDate dateTo,
                        Sort sort) {
                int safePage = Math.max(page, 0);
                int safeSize = Math.min(Math.max(size, 1), 100);
                Pageable pageable = PageRequest.of(safePage, safeSize, sort);

                LocalDateTime startAtFrom = dateFrom == null ? null : dateFrom.atStartOfDay();
                LocalDateTime startAtTo = dateTo == null ? null : dateTo.plusDays(1).atStartOfDay();

                Page<GroupClassBooking> result = groupClassBookingRepository.searchMyBookings(memberId, search,
                                matchesRoom(ClassRoom.YOGA_ROOM, search), matchesRoom(ClassRoom.BOXING_ROOM, search),
                                matchesRoom(ClassRoom.WEIGHT_ROOM, search),
                                status, startAtFrom, startAtTo, pageable);

                List<GroupClassBookingResponse> items = result.getContent().stream()
                                .map(booking -> new GroupClassBookingResponse(
                                                booking.getId(),
                                                booking.getSession().getId(),
                                                booking.getSession().getCourse().getName(),
                                                booking.getSession().getRoomName().getDisplayName(),
                                                booking.getSession().getStartAt(),
                                                booking.getSession().getEndAt(),
                                                booking.getStatus()))
                                .toList();

                return new PageResponse<>(items, safePage, safeSize, result.getTotalElements(), result.getTotalPages());
        }

        /**
         * 若場次已有會員報名，但管理者未在 request 指定 acknowledgement，則需二次確認。
         */
        private void requireAcknowledgementIfBooked(GroupClassSession session, boolean acknowledgeExistingBooking) {
                if (session.getBookedCount() > 0 && !acknowledgeExistingBooking) {
                        throw new ConfirmationRequiredException(
                                        "此場次(id=" + session.getId() + ")已有會員報名，請確認已通知會員後再次送出");
                }
        }

        /**
         * 管理者取消整個場次。商業規則檢查順序（不可變更）：
         * 1) 查無場次 → ResourceNotFoundException
         * 2) 若場次已有報名且未確認 → ConfirmationRequiredException
         * 3) 場次狀態設為 CANCELLED（先只改欄位，不在這裡存檔）
         * 4) 連鎖取消此場次所有狀態為 BOOKED 的 booking：每一筆都設為 CANCELLED，
         * 並同步呼叫 session.cancelBooking() 遞減 bookedCount，讓計數器跟實際報名數保持一致
         * （不然場次都取消了、bookedCount 卻還顯示有人報名，資料會不誠實）
         * 5) 全部修改都做完後，才呼叫一次 saveAndFlush()
         *
         * <p>
         * 第 5 步刻意把 saveAndFlush 留到最後、只呼叫一次：如果在第 3 步就先存一次、
         * 第 4 步的迴圈裡又繼續改 session（bookedCount），會變成「改一半、flush、又繼續改」，
         * 回傳的 DTO 讀到的 version／updatedAt 會是 flush 那個時間點的舊快照，
         * 少算後面迴圈做的異動——這是我們在 updateSession() 已經踩過一次的同一種陷阱。
         */
        @Transactional
        public GroupClassSessionResponse cancelSession(Integer sessionId, boolean acknowledgeExistingBooking) {
                GroupClassSession session = groupClassSessionRepository.findById(sessionId)
                                .orElseThrow(() -> new ResourceNotFoundException("查無此課程場次"));

                requireAcknowledgementIfBooked(session, acknowledgeExistingBooking);

                session.setStatus(GroupClassSessionStatus.CANCELLED);

                // 連鎖取消：同步把 session 自己的 bookedCount 一併扣減，
                // 跟每一筆 booking 的狀態變更放在同一批修改裡，一起等最後的 saveAndFlush。
                List<GroupClassBooking> bookings = groupClassBookingRepository
                                .findBySessionIdAndStatus(sessionId, GroupClassBookingStatus.BOOKED);
                for (GroupClassBooking b : bookings) {
                        b.setStatus(GroupClassBookingStatus.CANCELLED);
                        session.cancelBooking();
                        groupClassBookingRepository.save(b);
                }

                // 所有修改（狀態、bookedCount）都做完了，這裡才真正 flush 一次，
                // 確保回傳的 version／updatedAt／bookedCount 都是修改後的最新值。
                GroupClassSession saved = groupClassSessionRepository.saveAndFlush(session);

                return new GroupClassSessionResponse(saved.getId(), saved.getCourse().getId(),
                                saved.getCourse().getTrainer().getId(),
                                saved.getCourse().getTrainer().getEmployee().getFirstName() + " "
                                                + saved.getCourse().getTrainer().getEmployee().getLastName(),
                                saved.getCourse().getName(), saved.getRoomName(), saved.getRoomName().getDisplayName(),
                                saved.getRoomName().getColorHex(), saved.getStartAt(),
                                saved.getEndAt(), saved.getCapacity(), saved.getBookedCount(),
                                saved.getRemainingSeats(), saved.getBookedCount() >= saved.getCapacity(),
                                saved.getStatus(), saved.getVersion(), saved.getCreatedAt(), saved.getUpdatedAt());
        }

}