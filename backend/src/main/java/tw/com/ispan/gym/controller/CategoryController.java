package tw.com.ispan.gym.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tw.com.ispan.gym.entity.Category;
import tw.com.ispan.gym.service.CategoryService;

@RestController
@RequiredArgsConstructor
@Slf4j
public class CategoryController {
	
	private final CategoryService categoryService;
	
	@GetMapping("/api/categories")
	public ResponseEntity<List<Category>> getCategories(){
		log.info("Request to get all categories");
		return ResponseEntity.ok(categoryService.getAllCategories());
	}
}
