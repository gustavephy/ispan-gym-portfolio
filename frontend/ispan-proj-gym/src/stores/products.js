import { defineStore } from 'pinia';
import { ref } from 'vue';
import {
    getProductCategories,
    getProducts,
    getTrainerCoursesPublic,
    getProductById,
    getProductComments,
    submitProductComment
} from '@/api/product.js'

//載入商品分類
async function loadCategories(){
    try {
        const res = await getProductCategories()
    }
}