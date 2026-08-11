package tw.com.ispan.gym.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import tw.com.ispan.gym.entity.Category;

@Service
public class CategoryService {
	public List<Category> getAllCategories(){
		return new ArrayList<>();
	}
	
	public Category createCategory(Category category) {
		return category;
	}
	
	public void deleteCategory(Integer id) {
		
	}
	
}
