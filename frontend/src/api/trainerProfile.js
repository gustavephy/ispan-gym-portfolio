import api from './axios.instance.js'

// 教練本人查詢／修改「專業資料」，對應後端 TrainerProfileController 的 /api/trainer/profile。
// 一般個資（姓名／電話）不在這裡，走 employeeProfile.js；Email／密碼走帳號安全流程。
// 權限 hasRole('TRAINER')＝員工 ACTIVE 且有未軟刪除的 Trainer 列（只看 deletedAt，
// 不看 Trainer.status，故 UNAVAILABLE 教練仍可正常使用這兩支）。

/** 查自己的教練專業資料，需 TRAINER。回應 TrainerProfileResponse */
export const getMyTrainerProfile = () => api.get('/api/trainer/profile')

/**
 * 修改自己的教練專業資料，需 TRAINER。目前不做圖片上傳，profileImageUrl 僅接受文字網址。
 * @param {{ specialty: string, experience: string, bio: string, profileImageUrl?: string }} payload
 *   specialty ≤255、experience ≤1000、bio ≤5000 皆必填；profileImageUrl ≤255 選填
 */
export const updateMyTrainerProfile = (payload) => api.put('/api/trainer/profile', payload)
