package tw.com.ispan.gym.service.aihealthchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import tw.com.ispan.gym.dto.aihealthchat.InbodyRecordRequest;
import tw.com.ispan.gym.dto.aihealthchat.InbodyRecordResponse;
import tw.com.ispan.gym.entity.aihealthchat.InbodyRecord;
import tw.com.ispan.gym.exception.aihealthchat.DuplicateInbodyRecordException;
import tw.com.ispan.gym.exception.aihealthchat.ForbiddenInbodyAccessException;
import tw.com.ispan.gym.exception.aihealthchat.InbodyRecordNotFoundException;
import tw.com.ispan.gym.exception.aihealthchat.MissingInbodyMeasurementException;
import tw.com.ispan.gym.repository.aihealthchat.InbodyRecordRepository;

/**
 * 純 Mockito 單元測試：專案沒有內嵌測試資料庫（application.properties 接的是真的
 * SQL Server），故不用 @SpringBootTest／@DataJpaTest，改直接 mock Repository。
 * 因此「軟刪除後可重新新增」這一測試只驗證 Service 相信 exists 查詢語意（該查詢已
 * 排除軟刪除列）後的行為，不驗證資料庫篩選唯一索引本身是否真的這樣運作。
 */
class InbodyServiceTest {

    private static final Integer MEMBER_ID = 1;
    private static final LocalDate DATE = LocalDate.of(2026, 8, 12);

    private final InbodyRecordRepository records = mock(InbodyRecordRepository.class);
    private final InbodyService service = new InbodyService(records);

    private InbodyRecordRequest request(BigDecimal weight) {
        return new InbodyRecordRequest(DATE, null, weight, null, null, null);
    }

    private InbodyRecord existing(Integer id, Integer memberId) {
        return InbodyRecord.builder()
                .id(id)
                .memberId(memberId)
                .updateDate(DATE)
                .weight(BigDecimal.valueOf(70))
                .build();
    }

    // === 正常 CRUD ===========================================================

    @Test
    void 新增成功後回傳對應資料() {
        when(records.existsByMemberIdAndUpdateDateAndDeletedAtIsNull(MEMBER_ID, DATE)).thenReturn(false);
        when(records.save(any(InbodyRecord.class))).thenAnswer(inv -> {
            InbodyRecord r = inv.getArgument(0);
            r.setId(1);
            return r;
        });

        InbodyRecordResponse response = service.create(MEMBER_ID, request(BigDecimal.valueOf(70)));

        assertThat(response.id()).isEqualTo(1);
        assertThat(response.weight()).isEqualByComparingTo("70");
        verify(records).save(any(InbodyRecord.class));
    }

    @Test
    void 查詢歷史依日期新到舊() {
        when(records.findByMemberIdAndDeletedAtIsNullOrderByUpdateDateDesc(MEMBER_ID))
                .thenReturn(List.of(existing(1, MEMBER_ID)));

        List<InbodyRecordResponse> history = service.getHistory(MEMBER_ID);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).id()).isEqualTo(1);
    }

    @Test
    void 修改成功時更新欄位() {
        InbodyRecord record = existing(1, MEMBER_ID);
        when(records.findByIdAndDeletedAtIsNull(1)).thenReturn(Optional.of(record));
        when(records.saveAndFlush(any(InbodyRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        InbodyRecordResponse response = service.update(MEMBER_ID, 1, request(BigDecimal.valueOf(75)));

        assertThat(response.weight()).isEqualByComparingTo("75");
    }

    @Test
    void 軟刪除會標記刪除時間() {
        InbodyRecord record = existing(1, MEMBER_ID);
        when(records.findByIdAndDeletedAtIsNull(1)).thenReturn(Optional.of(record));

        service.softDelete(MEMBER_ID, 1);

        assertThat(record.getDeletedAt()).isNotNull();
    }

    // === 跨會員 403 ==========================================================

    @Test
    void 查詢不存在的紀錄為404() {
        when(records.findByIdAndDeletedAtIsNull(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOne(MEMBER_ID, 99))
                .isInstanceOf(InbodyRecordNotFoundException.class);
    }

    @Test
    void 存取他人紀錄為403() {
        InbodyRecord othersRecord = existing(1, 2);
        when(records.findByIdAndDeletedAtIsNull(1)).thenReturn(Optional.of(othersRecord));

        assertThatThrownBy(() -> service.getOne(MEMBER_ID, 1))
                .isInstanceOf(ForbiddenInbodyAccessException.class);
    }

    @Test
    void 修改他人紀錄為403() {
        InbodyRecord othersRecord = existing(1, 2);
        when(records.findByIdAndDeletedAtIsNull(1)).thenReturn(Optional.of(othersRecord));

        assertThatThrownBy(() -> service.update(MEMBER_ID, 1, request(BigDecimal.valueOf(70))))
                .isInstanceOf(ForbiddenInbodyAccessException.class);
    }

    // === 軟刪除後可重新新增 ===================================================

    @Test
    void 軟刪除後查詢排除故可重新新增() {
        when(records.existsByMemberIdAndUpdateDateAndDeletedAtIsNull(MEMBER_ID, DATE)).thenReturn(false);
        when(records.save(any(InbodyRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        InbodyRecordResponse response = service.create(MEMBER_ID, request(BigDecimal.valueOf(70)));

        assertThat(response).isNotNull();
    }

    // === 同日重複 409 ========================================================

    @Test
    void 同會員同天已有紀錄時新增為409() {
        when(records.existsByMemberIdAndUpdateDateAndDeletedAtIsNull(MEMBER_ID, DATE)).thenReturn(true);

        assertThatThrownBy(() -> service.create(MEMBER_ID, request(BigDecimal.valueOf(70))))
                .isInstanceOf(DuplicateInbodyRecordException.class);
    }

    @Test
    void 修改日期撞到別筆時為409() {
        InbodyRecord record = existing(1, MEMBER_ID);
        LocalDate otherDate = DATE.plusDays(1);
        when(records.findByIdAndDeletedAtIsNull(1)).thenReturn(Optional.of(record));
        when(records.existsByMemberIdAndUpdateDateAndDeletedAtIsNull(MEMBER_ID, otherDate)).thenReturn(true);

        InbodyRecordRequest changedDate = new InbodyRecordRequest(otherDate, null, BigDecimal.valueOf(70), null, null, null);

        assertThatThrownBy(() -> service.update(MEMBER_ID, 1, changedDate))
                .isInstanceOf(DuplicateInbodyRecordException.class);
    }

    @Test
    void 修改時日期沒變不會誤判重複() {
        InbodyRecord record = existing(1, MEMBER_ID);
        when(records.findByIdAndDeletedAtIsNull(1)).thenReturn(Optional.of(record));
        when(records.saveAndFlush(any(InbodyRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(MEMBER_ID, 1, request(BigDecimal.valueOf(72)));

        verify(records, org.mockito.Mockito.never())
                .existsByMemberIdAndUpdateDateAndDeletedAtIsNull(any(), any());
    }

    @Test
    void 建立時資料庫回報唯一索引衝突轉換為409() {
        when(records.existsByMemberIdAndUpdateDateAndDeletedAtIsNull(MEMBER_ID, DATE)).thenReturn(false);
        when(records.save(any(InbodyRecord.class)))
                .thenThrow(new DataIntegrityViolationException("UX_inbody_member_date"));

        assertThatThrownBy(() -> service.create(MEMBER_ID, request(BigDecimal.valueOf(70))))
                .isInstanceOf(DuplicateInbodyRecordException.class);
    }

    /**
     * 兩筆不同紀錄同時改成同一天，靠 saveAndFlush 讓衝突當下浮現並轉成 409，
     * 而不是拖到交易結束、變成查無原因的 500。
     */
    @Test
    void 修改時資料庫回報唯一索引衝突轉換為409() {
        InbodyRecord record = existing(1, MEMBER_ID);
        when(records.findByIdAndDeletedAtIsNull(1)).thenReturn(Optional.of(record));
        when(records.saveAndFlush(any(InbodyRecord.class)))
                .thenThrow(new DataIntegrityViolationException("UX_inbody_member_date"));

        assertThatThrownBy(() -> service.update(MEMBER_ID, 1, request(BigDecimal.valueOf(72))))
                .isInstanceOf(DuplicateInbodyRecordException.class);
    }

    /** 不相干的完整性違反不該被誤標成「同日重複」，要原樣往上丟。 */
    @Test
    void 不相干的資料庫例外不會被誤標為同日重複() {
        when(records.existsByMemberIdAndUpdateDateAndDeletedAtIsNull(MEMBER_ID, DATE)).thenReturn(false);
        DataIntegrityViolationException other = new DataIntegrityViolationException("CK_inbody_height violated");
        when(records.save(any(InbodyRecord.class))).thenThrow(other);

        assertThatThrownBy(() -> service.create(MEMBER_ID, request(BigDecimal.valueOf(70))))
                .isSameAs(other);
    }

    // === 空量測 400 ==========================================================

    @Test
    void 新增時五項量測值皆空為400() {
        InbodyRecordRequest empty = new InbodyRecordRequest(DATE, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(MEMBER_ID, empty))
                .isInstanceOf(MissingInbodyMeasurementException.class);

        verifyNoInteractions(records);
    }

    @Test
    void 修改時五項量測值皆空為400() {
        InbodyRecordRequest empty = new InbodyRecordRequest(DATE, null, null, null, null, null);

        assertThatThrownBy(() -> service.update(MEMBER_ID, 1, empty))
                .isInstanceOf(MissingInbodyMeasurementException.class);

        verifyNoInteractions(records);
    }
}
