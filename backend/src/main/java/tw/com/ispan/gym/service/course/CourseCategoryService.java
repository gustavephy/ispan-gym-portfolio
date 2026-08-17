package tw.com.ispan.gym.service.course;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.dto.course.CourseCategoryDto;
import tw.com.ispan.gym.entity.course.CourseCategory;
import tw.com.ispan.gym.enums.common.CategoryStatus;
import tw.com.ispan.gym.repository.course.CourseCategoryRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class CourseCategoryService {
	private final CourseCategoryRepository courseCategoryRepository;

	// 查詢所有課程類別
	// 【合併時的修改說明】原本是 private：當時還沒有 controller 呼叫它，
	// feat/coupon-controller 補上了 CourseCategoryController（查詢分類的公開端點），
	// 這個方法從此有了外部呼叫端，改為 public（作者分支上的同名方法本來就是 public）。
	public List<CourseCategoryDto> getAllCourseCategories() {
		log.info("Fetching all course categories");
		return courseCategoryRepository.findAll().stream()
				.map(entity -> CourseCategoryDto.from(entity))
				.collect(Collectors.toList());
	}

	// 新增課程分類
	public CourseCategoryDto createCourseCategory(CourseCategoryDto dto) {
		log.info("Creating new course category: name={}", dto);
		CategoryStatus status = dto.status() != null ? dto.status() : CategoryStatus.ACTIVE;
		int sortOrder = dto.sortOrder() != null ? dto.sortOrder() : 0;

		CourseCategory cat = new CourseCategory();
		cat.setName(dto.name());
		cat.setSortOrder(sortOrder);
		cat.setStatus(status);
		return CourseCategoryDto.from(courseCategoryRepository.save(cat));
	}

	// 更新課程分類
	public CourseCategoryDto updateCourseCategory(Integer id, CourseCategoryDto dto) {
		log.info("Updating course category with id={},name={}", id, dto.name());
		CourseCategory cat = courseCategoryRepository.findById(id)
				.orElseThrow(() -> new RuntimeException("找不到課程分類" + id));

		if (dto.name() != null) {
			cat.setName(dto.name());
		}
		if (dto.sortOrder() != null) {
			cat.setSortOrder(dto.sortOrder());
		}
		if (dto.status() != null) {
			cat.setStatus(dto.status());
		}

		return CourseCategoryDto.from(courseCategoryRepository.save(cat));
	}

	// 刪除課程分類
	public void deleteCourseCategory(Integer id) {
		log.info("Deleting course category with id={}", id);
		courseCategoryRepository.deleteById(id);
	}
}
