package tw.com.ispan.gym.service.aihealthchat;

import tw.com.ispan.gym.dto.aihealthchat.InbodyRecordRequest;
import tw.com.ispan.gym.dto.aihealthchat.InbodyRecordResponse;
import tw.com.ispan.gym.entity.aihealthchat.InbodyRecord;
import tw.com.ispan.gym.exception.aihealthchat.DuplicateInbodyRecordException;
import tw.com.ispan.gym.exception.aihealthchat.ForbiddenInbodyAccessException;
import tw.com.ispan.gym.exception.aihealthchat.InbodyRecordNotFoundException;
import tw.com.ispan.gym.exception.aihealthchat.MissingInbodyMeasurementException;
import tw.com.ispan.gym.repository.aihealthchat.InbodyRecordRepository;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InbodyService {
    private final InbodyRecordRepository records;

    public InbodyService(InbodyRecordRepository records) {
        this.records = records;
    }

    public List<InbodyRecordResponse> getHistory(Integer memberId) {
        return records.findByMemberIdAndDeletedAtIsNullOrderByUpdateDateDesc(memberId)
                .stream().map(this::toResponse).toList();
    }

    public InbodyRecordResponse getOne(Integer memberId, Integer recordId) {
        return toResponse(ownedRecord(memberId, recordId));
    }

    @Transactional
    public InbodyRecordResponse create(Integer memberId, InbodyRecordRequest request) {
        validateHasMeasurement(request);
        if (records.existsByMemberIdAndUpdateDateAndDeletedAtIsNull(memberId, request.updateDate())) {
            throw new DuplicateInbodyRecordException();
        }
        InbodyRecord record = InbodyRecord.builder()
                .memberId(memberId)
                .updateDate(request.updateDate())
                .height(request.height())
                .weight(request.weight())
                .bodyFat(request.bodyFat())
                .muscleMass(request.muscleMass())
                .bmi(request.bmi())
                .build();
        // 上面 exists 檢查與這裡的 insert 之間仍有極短的競爭窗口（例如使用者手滑送出兩次）；
        // id 是 IDENTITY 產生，save() 會立即送出 INSERT，唯一索引衝突會在這裡浮現，
        // 接住後轉成跟 exists 檢查一樣的 409，前端不會看到查無原因的 500。
        try {
            return toResponse(records.save(record));
        } catch (DataIntegrityViolationException ex) {
            throw translateIfDuplicateDate(ex);
        }
    }

    @Transactional
    public InbodyRecordResponse update(Integer memberId, Integer recordId, InbodyRecordRequest request) {
        validateHasMeasurement(request);
        InbodyRecord record = ownedRecord(memberId, recordId);
        boolean dateChanged = !record.getUpdateDate().equals(request.updateDate());
        if (dateChanged && records.existsByMemberIdAndUpdateDateAndDeletedAtIsNull(memberId, request.updateDate())) {
            throw new DuplicateInbodyRecordException();
        }
        record.setUpdateDate(request.updateDate());
        record.setHeight(request.height());
        record.setWeight(request.weight());
        record.setBodyFat(request.bodyFat());
        record.setMuscleMass(request.muscleMass());
        record.setBmi(request.bmi());
        // 兩筆不同紀錄同時改成同一天也會撞同一個唯一索引；跟 create() 一樣的競爭窗口，
        // 差別是 update 預設靠交易結束時的 dirty checking 才 flush，衝突不會浮現在這個
        // try 區塊裡。這裡主動 saveAndFlush 換取「衝突當下就能接住」。
        try {
            return toResponse(records.saveAndFlush(record));
        } catch (DataIntegrityViolationException ex) {
            throw translateIfDuplicateDate(ex);
        }
    }

    @Transactional
    public void softDelete(Integer memberId, Integer recordId) {
        ownedRecord(memberId, recordId).softDelete();
    }

    private InbodyRecord ownedRecord(Integer memberId, Integer recordId) {
        InbodyRecord record = records.findByIdAndDeletedAtIsNull(recordId)
                .orElseThrow(() -> new InbodyRecordNotFoundException(recordId));
        if (!record.getMemberId().equals(memberId)) {
            throw new ForbiddenInbodyAccessException(recordId);
        }
        return record;
    }

    /**
     * 只把 UX_inbody_member_date 衝突轉成「同日重複」；其餘完整性違反（未來若新增
     * 其他 DB 約束）原樣往上丟，避免被誤標成不相干的錯誤原因。
     */
    private RuntimeException translateIfDuplicateDate(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        String message = cause == null ? null : cause.getMessage();
        if (message != null && message.contains("UX_inbody_member_date")) {
            return new DuplicateInbodyRecordException();
        }
        return ex;
    }

    private void validateHasMeasurement(InbodyRecordRequest request) {
        if (request.height() == null && request.weight() == null && request.bodyFat() == null
                && request.muscleMass() == null && request.bmi() == null) {
            throw new MissingInbodyMeasurementException();
        }
    }

    private InbodyRecordResponse toResponse(InbodyRecord record) {
        return new InbodyRecordResponse(record.getId(), record.getUpdateDate(), record.getHeight(),
                record.getWeight(), record.getBodyFat(), record.getMuscleMass(), record.getBmi());
    }
}
