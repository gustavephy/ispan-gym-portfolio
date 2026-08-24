<template>
  <div class="page-container admin-dashboard">
    <!-- ERP 頂部標籤頁 -->
    <div class="erp-tabs" style="display: flex; gap: 1.5rem; border-bottom: 1.5px solid var(--border); margin-bottom: 2rem;">
      <button class="tab-btn" :class="{ active: currentTab === 'products' }" @click="currentTab = 'products'">
        課程方案管理
      </button>
      <button class="tab-btn" :class="{ active: currentTab === 'groupCourses' }" @click="currentTab = 'groupCourses'">
        團體課程管理
      </button>
    </div>

    <!-- TAB 1: 課程方案管理 (商品方案) -->
    <div v-if="currentTab === 'products'">
      <div class="page-header-row" style="display: flex; gap: 1rem; margin-bottom: 1.5rem;">
        <button class="btn btn-primary" @click="showAddProduct = !showAddProduct">
          <span>{{ showAddProduct ? '✕ 取消新增' : '＋ 新增課程方案' }}</span>
        </button>
        <button class="btn btn-outline" @click="showCategoryManager = true">
          <span>⚙ 管理課程分類</span>
        </button>
      </div>

      <!-- 新增課程方案表單 -->
      <Transition name="slide">
        <div v-if="showAddProduct" class="card add-form-card">
          <div class="form-header">
            <h4>新增課程方案資料</h4>
            <span class="form-helper">*所有欄位均為必填</span>
          </div>
          <form @submit.prevent="createProduct">
            <div class="form-grid-responsive">
              <div class="form-group">
                <label class="form-label">方案名稱</label>
                <input v-model="productForm.name" class="form-input" placeholder="例如: 燃脂 HIIT 體能課" required />
              </div>
              <div class="form-group">
                <label class="form-label">項目類型</label>
                <select v-model="productForm.type" class="form-input">
                  <option value="PRIVATE_COURSE">私教課方案</option>
                  <option value="GROUP_COURSE">團課方案</option>
                </select>
              </div>
              <div class="form-group">
                <label class="form-label">定價售價 (NT$)</label>
                <input v-model.number="productForm.price" type="number" class="form-input" min="1" placeholder="請輸入金額" required />
              </div>
              <div class="form-group" v-if="productForm.type === 'COURSE'">
                <label class="form-label">每班容納人數 / 上限</label>
                <input v-model="productForm.classSize" class="form-input" placeholder="例如: 1 對 1, 最多 8 人" required />
              </div>
              <div class="form-group" v-if="productForm.type === 'COURSE'">
                <label class="form-label">每份方案包含堂數 <span style="color:#94a3b8;font-size:0.8em">（購課後發放給學員的堂數）</span></label>
                <input v-model.number="productForm.sessionsPerUnit" type="number" class="form-input" min="1" max="999" placeholder="例如: 10" required />
              </div>
              <div class="form-group" v-if="productForm.type === 'SUBSCRIPTION'">
                <label class="form-label">天數設定 / 庫存</label>
                <input v-model.number="productForm.stock" type="number" class="form-input" min="1" placeholder="例如：9999" required />
              </div>
              <div v-if="productForm.type === 'COURSE'" class="form-group checkbox-group" style="display:flex; align-items:center; min-height: 38px;">
                <label class="checkbox-container">
                  <input v-model="productForm.isTrial" type="checkbox" class="custom-checkbox" />
                  <span class="checkmark"></span>
                  <span class="checkbox-label-text">開放免費體驗（每位會員限一次）</span>
                </label>
              </div>
            </div>
            <div class="form-group file-upload-group">
              <label class="form-label">課程照片 <span style="color:#94a3b8;font-size:0.8em">（限 5MB 以內）</span></label>
              <div class="file-upload-wrapper">
                <label class="custom-file-btn">
                  選擇照片
                  <input type="file" @change="e => uploadFile(e, 'add')" accept="image/*" class="hidden-file-input" />
                </label>
                <span v-if="uploadingFile" class="loading-spinner-inline"></span>
                <span class="file-status-text" v-if="productForm.imageUrl">已上傳圖片</span>
                <img v-if="productForm.imageUrl" :src="formatImageUrl(productForm.imageUrl)" class="form-preview-thumb" alt="Preview" />
              </div>
              <input v-model="productForm.imageUrl" class="form-input url-input" placeholder="或直接貼上外部圖片的 URL 網址..." />
            </div>
            <div class="form-group" style="margin-top: 1rem;">
              <label class="form-label">詳細說明介紹</label>
              <textarea v-model="productForm.description" class="form-input" rows="3" placeholder="請填寫此項目的特色、規格或課程內容介紹..."></textarea>
            </div>
            <div class="form-actions">
              <button type="submit" class="btn btn-primary">確認發佈上架</button>
            </div>
          </form>
        </div>
      </Transition>

      <!-- 搜尋與篩選條件區 -->
      <SearchFilter 
        v-model="filterParams"
        keyword-label="課程方案搜尋"
        keyword-placeholder="搜尋名稱、說明或教練..."
        date-label="上架日期範圍"
        @reset="resetFilters"
      />

      <!-- 快速分類篩選列 -->
      <CategoryFilter 
        v-model="activeTableFilter"
        :options="tableFilters"
      />

      <!-- 課程列表表格 -->
      <div class="admin-table card">
        <!-- Top Pagination Controls -->
        <div class="list-control-header bg-none" style="margin: 1rem 1rem 1.5rem 1rem; display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 1rem;">
          <div style="display: flex; align-items: center; gap: 1.5rem; flex-wrap: wrap;">
            <span class="total-count-text">共 {{ totalItems }} 筆項目</span>
            <div class="page-size-selector">
              <label>顯示數量：</label>
              <select v-model.number="itemsPerPage" class="select-sm">
                <option :value="5">5 筆</option>
                <option :value="10">10 筆</option>
                <option :value="20">20 筆</option>
                <option :value="50">50 筆</option>
              </select>
            </div>
          </div>

          <!-- 分頁 (Top) -->
          <div class="pagination" v-if="totalPages > 1" style="margin: 0; display: flex; gap: 0.5rem;">
            <button type="button" class="page-btn" :disabled="currentPage === 1" @click="currentPage--">‹</button>
            <template v-for="n in visiblePages" :key="n">
              <span v-if="n === '...'" class="page-ellipsis" style="display: flex; align-items: center; justify-content: center; width: 28px; height: 28px; color: var(--text-secondary); font-size: 0.8rem;">...</span>
              <button v-else type="button" :class="['page-btn', { active: currentPage === n }]" @click="currentPage = n">{{ n }}</button>
            </template>
            <button type="button" class="page-btn" :disabled="currentPage === totalPages" @click="currentPage++">›</button>
          </div>
        </div>

        <div class="product-table-wrap">
          <table style="table-layout: fixed; width: 100%;">
            <colgroup>
              <col class="hide-md" style="width: 50px;" />
              <col style="width: 80px;" />
              <col style="width: 240px;" />
              <col class="hide-md" style="width: 100px;" />
              <col class="hide-sm" style="width: 120px;" />
              <col style="width: 90px;" />
              <col class="hide-md" style="width: 80px;" />
              <col style="width: 120px;" />
            </colgroup>

            <thead>
              <tr>
                <th class="hide-md">ID</th>
                <th>課程圖片</th>
                <th>方案名稱 &amp; 說明</th>
                <th class="hide-md">類型</th>
                <th class="hide-sm">每班上限 / 堂數</th>
                <th>定價金額</th>
                <th class="hide-md">狀態</th>
                <th>功能操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in paginatedProducts" :key="p.id">
                <td class="hide-md" style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">#{{ p.id }}</td>
                <td>
                  <img :src="getProductImage(p)" class="product-thumb" alt="Course Image" />
                </td>
                <td class="product-name-cell" style="white-space: normal; word-break: break-word;">
                  <div class="name-text">{{ p.name }}</div>
                  <div class="desc-text hide-sm" :title="p.description">{{ p.description || '無詳細說明' }}</div>
                  <div v-if="p.type === 'COURSE'" class="trainer-name-label" style="font-size: 0.72rem; color: var(--primary); font-weight: 700; margin-top: 0.2rem;">
                    指導教練: {{ p.trainerName || '多位教練/未分配' }}
                  </div>
                </td>
                <td class="hide-md" style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
                  <span :class="['badge', (p.type === 'PRIVATE_COURSE' || (p.type === 'COURSE' && (p.name.includes('一對一') || p.name.includes('私教')))) ? 'badge-course' : (p.type === 'GROUP_COURSE' || p.type === 'COURSE') ? 'badge-warning' : 'badge-subscription']">
                    {{ (p.type === 'PRIVATE_COURSE' || (p.type === 'COURSE' && (p.name.includes('一對一') || p.name.includes('私教')))) ? '私教課方案' : (p.type === 'GROUP_COURSE' || p.type === 'COURSE') ? '團課方案' : '月卡訂閱' }}
                  </span>
                </td>
                <td class="hide-sm" style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
                  <span v-if="p.type === 'COURSE'">
                    {{ p.classSize || '未指定' }} / {{ p.sessionsPerUnit || 1 }} 堂
                  </span>
                  <span v-else class="badge badge-success">無限暢練</span>
                </td>
                <td class="price-text" style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">NT$ {{ Number(p.price).toLocaleString() }}</td>
                <td class="hide-md">
                  <div class="status-cell-wrap">
                    <span :class="['status-dot', p.status === 1 || p.status === 'ACTIVE' ? 'active' : 'inactive']"></span>
                    <span :class="['status-text-label', p.status === 1 || p.status === 'ACTIVE' ? 'text-active' : 'text-inactive']">
                      {{ p.status === 1 || p.status === 'ACTIVE' ? '上架中' : '已下架' }}
                    </span>
                  </div>
                </td>
                <td>
                  <div class="action-buttons-wrap">
                    <button class="btn btn-edit edit-btn-style" @click="openEditModal(p)">編輯</button>
                    <button v-if="p.status === 1 || p.status === 'ACTIVE'" class="btn btn-danger deactive-btn-style" @click="deleteProduct(p.id)">下架</button>
                  </div>
                </td>
              </tr>
              <tr v-if="filteredTableProducts.length === 0">
                <td colspan="8" style="text-align: center; padding: 3rem; color: #94a3b8;">
                  沒有符合當前篩選條件的課程方案項目
                </td>
              </tr>
            </tbody>
          </table>
        </div><!-- /.product-table-wrap -->

        <!-- 分頁 -->
        <div class="pagination" v-if="totalPages > 1" style="margin: 1rem 0; justify-content: center; display: flex; gap: 0.5rem;">
          <button type="button" class="page-btn" :disabled="currentPage === 1" @click="currentPage--">‹</button>
          <template v-for="n in visiblePages" :key="n">
            <span v-if="n === '...'" class="page-ellipsis" style="display: flex; align-items: center; justify-content: center; width: 28px; height: 28px; color: var(--text-secondary); font-size: 0.8rem;">...</span>
            <button
              v-else
              type="button"
              :class="['page-btn', { active: currentPage === n }]"
              @click="currentPage = n"
            >
              {{ n }}
            </button>
          </template>
          <button type="button" class="page-btn" :disabled="currentPage === totalPages" @click="currentPage++">›</button>
        </div>
      </div>

    </div>

    <!-- TAB 2: 團體課程管理 -->
    <div v-else-if="currentTab === 'groupCourses'">
      <div class="page-header-row" style="display: flex; gap: 1rem; margin-bottom: 1.5rem;">
        <button class="btn btn-primary" @click="showAddGroupCourse = !showAddGroupCourse">
          <span>{{ showAddGroupCourse ? '✕ 取消新增' : '＋ 新增團體課程' }}</span>
        </button>
        <button class="btn btn-outline" @click="showCategoryManager = true">
          <span>⚙ 管理課程分類</span>
        </button>
      </div>

      <!-- 新增團體課程表單 -->
      <Transition name="slide">
        <div v-if="showAddGroupCourse" class="card add-form-card">
          <div class="form-header">
            <h4>新增團體課程資料</h4>
            <span class="form-helper">*請確實填寫指導教練與課程資訊</span>
          </div>
          <form @submit.prevent="createGroupCourse">
            <div class="form-grid-responsive">
              <div class="form-group">
                <label class="form-label">課程名稱</label>
                <input v-model="groupCourseForm.name" class="form-input" placeholder="例如: 燃脂有氧、基礎空中瑜珈" required />
              </div>
              <div class="form-group">
                <label class="form-label">指導教練</label>
                <select v-model="groupCourseForm.trainerId" class="form-input" required>
                  <option :value="null" disabled>請選擇指導教練</option>
                  <option v-for="t in trainersList" :key="t.id" :value="t.id">
                    {{ t.name }} ({{ t.specialty }})
                  </option>
                </select>
              </div>
              <div class="form-group">
                <label class="form-label">每班上限人數</label>
                <input v-model.number="groupCourseForm.maxCapacity" type="number" class="form-input" min="1" max="100" required />
              </div>
              <div class="form-group">
                <label class="form-label">課程分類</label>
                <select v-model="groupCourseForm.categoryId" class="form-input" required>
                  <option :value="null" disabled>請選擇分類</option>
                  <option v-for="cat in courseCategories" :key="cat.id" :value="cat.id">
                    {{ cat.name }}
                  </option>
                </select>
              </div>
              <div class="form-group checkbox-group" style="display:flex; align-items:center; min-height: 38px;">
                <label class="checkbox-container">
                  <input v-model="groupCourseForm.isTrial" type="checkbox" class="custom-checkbox" />
                  <span class="checkmark"></span>
                  <span class="checkbox-label-text">開放免費體驗</span>
                </label>
              </div>
            </div>
            <div class="form-group file-upload-group" style="margin-top: 1rem;">
              <label class="form-label">課程照片</label>
              <div class="file-upload-wrapper">
                <label class="custom-file-btn">
                  選擇照片
                  <input type="file" @change="e => uploadFile(e, 'addGroupCourse')" accept="image/*" class="hidden-file-input" />
                </label>
                <span v-if="uploadingFile" class="loading-spinner-inline"></span>
                <span class="file-status-text" v-if="groupCourseForm.imageUrl">已上傳圖片</span>
                <img v-if="groupCourseForm.imageUrl" :src="formatImageUrl(groupCourseForm.imageUrl)" class="form-preview-thumb" alt="Preview" />
              </div>
              <input v-model="groupCourseForm.imageUrl" class="form-input url-input" placeholder="圖片位址 URL" />
            </div>
            <div class="form-group" style="margin-top: 1rem;">
              <label class="form-label">詳細說明介紹</label>
              <textarea v-model="groupCourseForm.description" class="form-input" rows="3" placeholder="請填寫課程特色、難易度、注意事項..."></textarea>
            </div>
            <div class="form-actions">
              <button type="submit" class="btn btn-primary" :disabled="saving">確認新增上傳</button>
            </div>
          </form>
        </div>
      </Transition>


      <!-- 統一搜尋與篩選列 -->
      <SearchFilter
        v-model="gcFilterParams"
        keyword-label="團體課程搜尋"
        keyword-placeholder="搜尋課程名稱、指導教練姓名..."
        date-label="上架日期範圍"
        @search="onGcSearch"
        @reset="resetGcFilters"
      />



      <!-- 快速狀態篩選 -->
      <CategoryFilter
        v-model="gcActiveFilter"
        label="快速狀態篩選："
        :options="gcCategoryFilters"
      />

      <!-- 團體課程列表 -->
      <div class="admin-table card">
        <!-- 分頁與筆數控制列 -->
        <div class="list-control-header bg-none" style="margin: 1rem 1rem 1.5rem 1rem; display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 1rem;">
          <div style="display: flex; align-items: center; gap: 1.5rem; flex-wrap: wrap;">
            <span class="total-count-text">共 {{ gcTotalItems }} 筆課程</span>
            <div class="page-size-selector">
              <label>顯示數量：</label>
              <select v-model.number="gcItemsPerPage" class="select-sm" @change="gcCurrentPage = 1">
                <option :value="5">5 筆</option>
                <option :value="10">10 筆</option>
                <option :value="20">20 筆</option>
              </select>
            </div>
          </div>
          <!-- 分頁頁碼 -->
          <div class="pagination" v-if="gcTotalPages > 1" style="margin: 0; display: flex; gap: 0.5rem;">
            <button type="button" class="page-btn" :disabled="gcCurrentPage === 1" @click="gcCurrentPage--">‹</button>
            <template v-for="n in gcVisiblePages" :key="n">
              <span v-if="n === '...'" class="page-ellipsis" style="display:flex;align-items:center;justify-content:center;width:28px;height:28px;color:var(--text-secondary);font-size:0.8rem;">...</span>
              <button v-else type="button" :class="['page-btn', { active: gcCurrentPage === n }]" @click="gcCurrentPage = n">{{ n }}</button>
            </template>
            <button type="button" class="page-btn" :disabled="gcCurrentPage === gcTotalPages" @click="gcCurrentPage++">›</button>
          </div>
        </div>

        <div class="product-table-wrap">
          <table style="table-layout: fixed; width: 100%;">
            <colgroup>
              <col style="width: 7%;" />
              <col style="width: 10%;" />
              <col style="width: 25%;" />
              <col style="width: 15%;" />
              <col style="width: 10%;" />
              <col style="width: 11%;" />
              <col style="width: 10%;" />
              <col style="width: 10%;" />
              <col style="width: 12%;" />
            </colgroup>
            <thead>
              <tr>
                <th style="width: 60px;">ID</th>
                <th style="width: 100px;">圖片</th>
                <th style="width: 300px;">課程名稱 &amp; 說明</th>
                <th style="width: 150px;">指導教練</th>
                <th style="width: 100px;">人數上限</th>
                <th style="width: 120px;">課程分類</th>
                <th style="width: 100px;">免費體驗</th>
                <th style="width: 100px;">狀態</th>
                <th style="width: 150px;">操作</th>
              </tr>
            </thead>


            <tbody>
              <tr v-for="c in groupCoursesList" :key="c.groupCourseId">
                <td>#{{ c.groupCourseId }}</td>
                <td>
                  <img :src="c.imageUrl ? formatImageUrl(c.imageUrl) : 'https://images.unsplash.com/photo-1517838277536-f5f99be501cd?w=400&auto=format&fit=crop&q=60'" class="product-thumb" alt="Course Image" />
                </td>
                <td class="product-name-cell">
                  <div class="name-text">{{ c.name }}</div>
                  <div class="desc-text" :title="c.description">{{ c.description || '無詳細說明' }}</div>
                </td>
                <td class="trainer-cell">
                  <div style="font-weight: 600; color: var(--primary-dark);">{{ c.trainer?.name || '未分配' }}</div>
                  <div style="font-size: 0.7rem; color: #64748b;">{{ c.trainer?.email }}</div>
                </td>

                <td style="font-weight: 600;">{{ c.maxCapacity }} 人</td>
                <td>
                  <span class="badge badge-scope">{{ c.category?.name || '未分類' }}</span>
                </td>
                <td>
                  <span :class="['badge', c.isTrial ? 'badge-success' : 'badge-goods']">
                    {{ c.isTrial ? '開放' : '不開放' }}
                  </span>
                </td>
                <td>
                  <div class="status-cell-wrap">
                    <span :class="['status-dot', c.status === 'ACTIVE' ? 'active' : 'inactive']"></span>
                    <span :class="['status-text-label', c.status === 'ACTIVE' ? 'text-active' : 'text-inactive']">
                      {{ c.status === 'ACTIVE' ? '上架中' : '已下架' }}
                    </span>
                  </div>
                </td>
                <td>
                  <div class="action-buttons-wrap">
                    <button class="btn btn-edit edit-btn-style" @click="openEditGroupCourseModal(c)">編輯</button>
                    <button v-if="c.status === 'ACTIVE'" class="btn btn-danger deactive-btn-style" @click="deleteGroupCourse(c.groupCourseId)">下架</button>
                  </div>
                </td>
              </tr>
              <tr v-if="groupCoursesList.length === 0">
                <td colspan="9" style="text-align: center; padding: 3rem; color: #94a3b8;">
                  查無任何團體課程項目，請點擊上方按鈕新增上傳。
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- 編輯課程方案 Modal -->
    <AppModal v-model:visible="showEditProduct" title="編輯課程方案內容" icon="" size="md">
      <form id="editProductForm" @submit.prevent="updateProduct">
        <div class="form-group">
          <label class="form-label">方案名稱</label>
          <input v-model="editForm.name" class="form-input" required />
        </div>
        <div class="form-grid-responsive">
          <div class="form-group">
            <label class="form-label">項目類型</label>
            <select v-model="editForm.type" class="form-input" disabled>
              <option value="PRIVATE_COURSE">私教課方案</option>
              <option value="GROUP_COURSE">團課方案</option>
            </select>
          </div>
          <div class="form-group">
            <label class="form-label">售價 (NT$)</label>
            <input v-model.number="editForm.price" type="number" class="form-input" min="1" required />
          </div>
          <div class="form-group" v-if="editForm.type === 'COURSE'">
            <label class="form-label">每班容納人數 / 上限</label>
            <input v-model="editForm.classSize" class="form-input" placeholder="例如: 1 對 1, 最多 8 人" />
          </div>
          <div class="form-group" v-if="editForm.type === 'COURSE'">
            <label class="form-label">每份方案包含堂數 <span style="color:#94a3b8;font-size:0.8em">（購課後發放給學員）</span></label>
            <input v-model.number="editForm.sessionsPerUnit" type="number" class="form-input" min="1" max="999" placeholder="例如: 10" />
          </div>
          <div class="form-group" v-if="editForm.type === 'SUBSCRIPTION'">
            <label class="form-label">天數設定 / 限制</label>
            <input v-model.number="editForm.stock" type="number" class="form-input" min="1" />
          </div>
          <div v-if="editForm.type === 'COURSE'" class="form-group checkbox-group" style="display:flex; align-items:center; min-height: 38px;">
            <label class="checkbox-container">
              <input v-model="editForm.isTrial" type="checkbox" class="custom-checkbox" />
              <span class="checkmark"></span>
              <span class="checkbox-label-text">開放免費體驗（每位會員限一次）</span>
            </label>
          </div>
        </div>
        <div class="form-group" style="margin-top: 1rem;">
          <label class="form-label">照片設定 <span style="color:#94a3b8;font-size:0.8em">（限 5MB 以內）</span></label>
          <div class="file-upload-wrapper">
            <label class="custom-file-btn">
              重新上傳
              <input type="file" @change="e => uploadFile(e, 'edit')" accept="image/*" class="hidden-file-input" />
            </label>
            <span v-if="uploadingFile" class="loading-spinner-inline"></span>
            <img v-if="editForm.imageUrl" :src="formatImageUrl(editForm.imageUrl)" class="form-preview-thumb" alt="Preview" />
          </div>
          <input v-model="editForm.imageUrl" class="form-input url-input" placeholder="圖片位址 URL" />
        </div>
        <div class="form-group" style="margin-top: 1rem;">
          <label class="form-label">詳細說明介紹</label>
          <textarea v-model="editForm.description" class="form-input" rows="3"></textarea>
        </div>
      </form>
      <template #footer>
        <button type="button" class="btn btn-ghost" @click="showEditProduct = false">取消</button>
        <button type="submit" form="editProductForm" class="btn btn-primary">保存修改資料</button>
      </template>
    </AppModal>

    <!-- 編輯團體課程 Modal -->
    <AppModal v-model:visible="showEditGroupCourse" title="編輯團體課程內容" icon="" size="md">
      <form id="editGroupCourseForm" @submit.prevent="updateGroupCourse">
        <div class="form-group">
          <label class="form-label">課程名稱</label>
          <input v-model="editGroupCourseForm.name" class="form-input" required />
        </div>
        <div class="form-grid-responsive">
          <div class="form-group">
            <label class="form-label">指導教練</label>
            <select v-model="editGroupCourseForm.trainerId" class="form-input" required>
              <option :value="null" disabled>請選擇指導教練</option>
              <option v-for="t in trainersList" :key="t.id" :value="t.id">
                {{ t.name }} ({{ t.specialty }})
              </option>
            </select>
          </div>
          <div class="form-group">
            <label class="form-label">每班上限人數</label>
            <input v-model.number="editGroupCourseForm.maxCapacity" type="number" class="form-input" min="1" max="100" required />
          </div>
          <div class="form-group">
            <label class="form-label">課程分類</label>
            <select v-model="editGroupCourseForm.categoryId" class="form-input" required>
              <option :value="null" disabled>請選擇分類</option>
              <option v-for="cat in courseCategories" :key="cat.id" :value="cat.id">
                {{ cat.name }}
              </option>
            </select>
          </div>
          <div class="form-group checkbox-group" style="display:flex; align-items:center; min-height: 38px;">
            <label class="checkbox-container">
              <input v-model="editGroupCourseForm.isTrial" type="checkbox" class="custom-checkbox" />
              <span class="checkmark"></span>
              <span class="checkbox-label-text">開放免費體驗</span>
            </label>
          </div>
          <div class="form-group">
            <label class="form-label">課程狀態</label>
            <select v-model="editGroupCourseForm.status" class="form-input" required>
              <option value="ACTIVE">上架中 (ACTIVE)</option>
              <option value="INACTIVE">已下架 (INACTIVE)</option>
            </select>
          </div>
        </div>
        <div class="form-group" style="margin-top: 1rem;">
          <label class="form-label">照片設定</label>
          <div class="file-upload-wrapper">
            <label class="custom-file-btn">
              重新上傳
              <input type="file" @change="e => uploadFile(e, 'editGroupCourse')" accept="image/*" class="hidden-file-input" />
            </label>
            <span v-if="uploadingFile" class="loading-spinner-inline"></span>
            <img v-if="editGroupCourseForm.imageUrl" :src="formatImageUrl(editGroupCourseForm.imageUrl)" class="form-preview-thumb" alt="Preview" />
          </div>
          <input v-model="editGroupCourseForm.imageUrl" class="form-input url-input" placeholder="圖片位址 URL" />
        </div>
        <div class="form-group" style="margin-top: 1rem;">
          <label class="form-label">詳細說明介紹</label>
          <textarea v-model="editGroupCourseForm.description" class="form-input" rows="3"></textarea>
        </div>
      </form>
      <template #footer>
        <button type="button" class="btn btn-ghost" @click="showEditGroupCourse = false">取消</button>
        <button type="submit" form="editGroupCourseForm" class="btn btn-primary" :disabled="saving">保存修改資料</button>
      </template>
    </AppModal>

    <!-- 分類管理 Modal -->
    <AppModal v-model:visible="showCategoryManager" title="管理課程分類" icon="" size="md">
      <div class="category-manager-content">
        <!-- 新增分類表單 -->
        <form @submit.prevent="createCategory" class="card" style="padding: 1rem; margin-bottom: 1.5rem; background: var(--bg-soft);">
          <h5 style="margin-bottom: 0.75rem; font-weight: 700;">新增分類</h5>
          <div style="display: flex; gap: 0.5rem; flex-wrap: wrap;">
            <input v-model="newCategoryForm.name" class="form-input" style="flex: 2; min-width: 150px;" placeholder="分類名稱，例如：團課" required />
            <button type="submit" class="btn btn-primary" style="padding: 0.5rem 1rem;">新增</button>
          </div>
        </form>

        <!-- 分類列表 -->
        <div class="card" style="padding: 1rem; max-height: 300px; overflow-y: auto;">
          <h5 style="margin-bottom: 0.75rem; font-weight: 700;">目前分類清單</h5>
          <table style="width: 100%; border-collapse: collapse;">
            <thead>
              <tr style="border-bottom: 1px solid var(--border); text-align: left;">
                <th style="padding: 0.5rem;">名稱</th>
                <th style="padding: 0.5rem; text-align: right;">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="cat in courseCategories" :key="cat.id" style="border-bottom: 1px solid var(--border-light);">
                <td style="padding: 0.5rem; font-weight: 600;">{{ cat.name }}</td>
                <td style="padding: 0.5rem; text-align: right;">
                  <button @click="deleteCategory(cat)" class="btn btn-danger btn-sm" style="padding: 0.25rem 0.5rem; font-size: 0.75rem;">刪除</button>
                </td>
              </tr>
              <tr v-if="courseCategories.length === 0">
                <td colspan="2" style="text-align: center; padding: 1rem; color: var(--text-muted);">無分類資料</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <template #footer>
        <button type="button" class="btn btn-primary" @click="showCategoryManager = false">完成</button>
      </template>
    </AppModal>

    <div class="dashboard-footer-blank"></div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, inject, watch } from 'vue'
import { storeToRefs } from 'pinia'
import AppModal from '@/components/AppModal.vue'
import SearchFilter from '@/components/SearchFilter.vue'
import CategoryFilter from '@/components/CategoryFilter.vue'
import { formatImageUrl } from '@/utils/image.js'
import api from '@/api/axios.instance.js'

// 為了方案B，我們將產品與分類改為直接打後端 API，不用不存在的 useEmployeeStore
const products = ref([])
const courseCategories = ref([])

const showToast = inject('showToast')
// [響應式狀態] coursesList (courses清單)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const coursesList = ref([])

// [響應式狀態] showAddProduct (顯示新增product)
// 為什麼要這麼做：開啟或切換特定版面、提示或按鈕的顯示狀態，實現動態的 UI 響應式交互。
const showAddProduct = ref(false)
// [響應式狀態] showEditProduct (顯示編輯product)
// 為什麼要這麼做：開啟或切換特定版面、提示或按鈕的顯示狀態，實現動態的 UI 響應式交互。
const showEditProduct = ref(false)
// [響應式狀態] showCategoryManager (顯示categorymanager)
// 為什麼要這麼做：開啟或切換特定版面、提示或按鈕的顯示狀態，實現動態的 UI 響應式交互。
const showCategoryManager = ref(false)
// [響應式狀態] uploadingFile (uploadingfile)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const uploadingFile = ref(false)
// [響應式狀態] activeTableFilter (activetable篩選)
// 為什麼要這麼做：依據指定屬性將不符合條件的陣列元素濾除，確保畫面只呈現使用者感興趣的資料。
const activeTableFilter = ref('ALL')

// ERP Tabs 控制
// [響應式狀態] currentTab (currenttab)
// 為什麼要這麼做：目前選取的資料，以便進行單一資料展示、編輯或預約操作。
const currentTab = ref('products')
// [響應式狀態] showAddGroupCourse (顯示新增groupcourse)
// 為什麼要這麼做：開啟或切換特定版面、提示或按鈕的顯示狀態，實現動態的 UI 響應式交互。
const showAddGroupCourse = ref(false)
// [響應式狀態] showEditGroupCourse (顯示編輯groupcourse)
// 為什麼要這麼做：開啟或切換特定版面、提示或按鈕的顯示狀態，實現動態的 UI 響應式交互。
const showEditGroupCourse = ref(false)
// [響應式狀態] saving (saving)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const saving = ref(false)
// [響應式狀態] loading (loading)
// 為什麼要這麼做：是否正在載入資料的布林標記，用以在請求完成前控制 Spinner 載入動畫的顯示。
const loading = ref(false)

// 團課分頁與篩選狀態
// [響應式狀態] groupCoursesList (groupcourses清單)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const groupCoursesList = ref([])
// [響應式狀態] gcCurrentPage (gccurrentpage)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const gcCurrentPage = ref(1)
// [響應式狀態] gcItemsPerPage (gcitemsperpage)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const gcItemsPerPage = ref(10)
// [響應式狀態] gcTotalPages (gctotalpages)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const gcTotalPages = ref(1)
// [響應式狀態] gcTotalItems (gctotalitems)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const gcTotalItems = ref(0)
// [響應式狀態] gcFilterParams (gc篩選params)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const gcFilterParams = ref({
  keyword: '',
  startDate: '',
  endDate: ''
})
// [響應式狀態] gcActiveFilter (gcactive篩選)
// 為什麼要這麼做：依據指定屬性將不符合條件的陣列元素濾除，確保畫面只呈現使用者感興趣的資料。
const gcActiveFilter = ref('ALL')
// [計算屬性] gcCategoryFilters (gccategoryfilters)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const gcCategoryFilters = computed(() => [
  { value: 'ALL',      label: '全部課程' },
  { value: 'ACTIVE',   label: '上架中' },
  { value: 'INACTIVE', label: '已下架' }
])
// [響應式狀態] trainersList (trainers清單)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const trainersList = ref([])


// [響應式狀態] groupCourseForm (groupcourseform)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const groupCourseForm = ref({
  name: '',
  trainerId: null,
  maxCapacity: 20,
  categoryId: null,
  description: '',
  imageUrl: '',
  isTrial: false,
  status: 'ACTIVE'
})

// [響應式狀態] editGroupCourseForm (編輯groupcourseform)
// 為什麼要這麼做：開啟編輯模式或彈窗，並預先載入欲修改項目的現有資料，提供良好的使用者修改體驗。
const editGroupCourseForm = ref({
  groupCourseId: null,
  name: '',
  trainerId: null,
  maxCapacity: 20,
  categoryId: null,
  description: '',
  imageUrl: '',
  isTrial: false,
  status: 'ACTIVE'
})

// 搜尋與篩選狀態
// [響應式狀態] filterParams (篩選params)
// 為什麼要這麼做：依據指定屬性將不符合條件的陣列元素濾除，確保畫面只呈現使用者感興趣的資料。
const filterParams = ref({
  keyword: '',
  startDate: '',
  endDate: ''
})

// [功能方法] resetFilters (重置filters)
// 為什麼要這麼做：將所有篩選條件、分頁或表單狀態還原至預設初值，解決狀態殘留引起的查詢問題。
function resetFilters() {
  filterParams.value.keyword = ''
  filterParams.value.startDate = ''
  filterParams.value.endDate = ''
}

// 分頁狀態
// [響應式狀態] currentPage (currentpage)
// 為什麼要這麼做：目前選取的資料，以便進行單一資料展示、編輯或預約操作。
const currentPage = ref(1)
// [響應式狀態] itemsPerPage (itemsperpage)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const itemsPerPage = ref(10)
// [響應式狀態] totalItems (totalitems)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const totalItems = ref(0)
// [響應式狀態] totalPages (totalpages)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const totalPages = ref(1)

// 當篩選條件改變時，重置頁碼到第一頁並載入資料
// [監聽器] 監聽特定狀態變化
// 為什麼要這麼做：當監聽的數據或屬性發生異動時，即時觸發與其關聯的連動業務邏輯、重新計算數值或重新載入資料。
watch([activeTableFilter, () => filterParams.value.keyword, () => filterParams.value.startDate, () => filterParams.value.endDate], () => {

  currentPage.value = 1
  loadProducts()
})

// 當頁碼或每頁數量變更時，重新載入資料
// [監聽器] 監聽特定狀態變化
// 為什麼要這麼做：當監聽的數據或屬性發生異動時，即時觸發與其關聯的連動業務邏輯、重新計算數值或重新載入資料。
watch([currentPage, itemsPerPage], () => {
  loadProducts()
})


// products and courseCategories are now defined as local refs at the top of script setup

// [響應式狀態] productForm (productform)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const productForm = ref({ name: '', type: 'PRIVATE_COURSE', price: null, stock: 9999, description: '', imageUrl: '', productCategoryId: null, classSize: '', isTrial: false, sessionsPerUnit: 1 })
// [響應式狀態] editForm (編輯form)
// 為什麼要這麼做：開啟編輯模式或彈窗，並預先載入欲修改項目的現有資料，提供良好的使用者修改體驗。
const editForm = ref({ id: null, name: '', type: 'PRIVATE_COURSE', price: null, stock: 9999, description: '', imageUrl: '', productCategoryId: null, classSize: '', isTrial: false, sessionsPerUnit: 1 })
// [響應式狀態] newCategoryForm (newcategoryform)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const newCategoryForm = ref({ name: '', categoryType: 'COURSE', iconUrl: '', sortOrder: 0 })

// [計算屬性] tableFilters (tablefilters)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const tableFilters = computed(() => {
  return [
    { value: 'ALL', label: '全部課程方案' },
    { value: 'PRIVATE_COURSE', label: '私教課方案' },
    { value: 'GROUP_COURSE', label: '團課方案' },
    { value: 'INACTIVE', label: '已下架項目' }
  ]
})

// [計算屬性] filteredTableProducts (filteredtableproducts)
// 為什麼要這麼做：依據指定屬性將不符合條件的陣列元素濾除，確保畫面只呈現使用者感興趣的資料。
const filteredTableProducts = computed(() => {
  return coursesList.value
})

// [計算屬性] paginatedProducts (paginatedproducts)
// 為什麼要這麼做：在架商品清單，提供頁面渲染商品列表的資料來源。
const paginatedProducts = computed(() => {
  return coursesList.value
})



// [計算屬性] visiblePages (visiblepages)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const visiblePages = computed(() => {
  const current = currentPage.value
  const total = totalPages.value
  if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1)
  const pages = []
  pages.push(1)
  let start = Math.max(2, current - 2)
  let end = Math.min(total - 1, current + 2)
  if (current <= 4) { end = 5 } else if (current >= total - 3) { start = total - 4 }
  if (start > 2) pages.push('...')
  for (let i = start; i <= end; i++) pages.push(i)
  if (end < total - 1) pages.push('...')
  pages.push(total)
  return pages
})

// [計算屬性] gcVisiblePages (gcvisiblepages)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
const gcVisiblePages = computed(() => {
  const current = gcCurrentPage.value
  const total = gcTotalPages.value
  if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1)
  const pages = []
  pages.push(1)
  let start = Math.max(2, current - 2)
  let end = Math.min(total - 1, current + 2)
  if (current <= 4) { end = 5 } else if (current >= total - 3) { start = total - 4 }
  if (start > 2) pages.push('...')
  for (let i = start; i <= end; i++) pages.push(i)
  if (end < total - 1) pages.push('...')
  pages.push(total)
  return pages
})

// [功能方法] getProductImage (取得productimage)
// 為什麼要這麼做：向伺服器查詢並獲取指定資料，以供畫面渲染或後續邏輯判斷使用。
function getProductImage(p) {
  if (p.imageUrl) return formatImageUrl(p.imageUrl)
  return 'https://images.unsplash.com/photo-1517838277536-f5f99be501cd?w=400&auto=format&fit=crop&q=60'
}

// [功能方法] uploadFile (uploadfile)
// 為什麼要這麼做：處理或儲存與其關聯的資料狀態，以維持頁面運作與業務邏輯的正確性。
async function uploadFile(event, mode) {
  const file = event.target.files[0]
  if (!file) return
  if (file.size > 5 * 1024 * 1024) {
    showToast('照片檔案不能超過 5MB', 'error')
    return
  }
  uploadingFile.value = true
  try {
    const formData = new FormData()
    formData.append('file', file)
    const res = await api.post('/api/employee/products/upload', formData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    })
    uploadingFile.value = false
    if (res.data && res.data.url) {
      if (mode === 'add') {
        productForm.value.imageUrl = res.data.url
      } else if (mode === 'edit') {
        editForm.value.imageUrl = res.data.url
      } else if (mode === 'addGroupCourse') {
        groupCourseForm.value.imageUrl = res.data.url
      } else if (mode === 'editGroupCourse') {
        editGroupCourseForm.value.imageUrl = res.data.url
      }
      showToast('圖片上傳成功')
    } else {
      showToast('上傳失敗', 'error')
    }
  } catch (err) {
    uploadingFile.value = false
    showToast(err.message || '上傳失敗', 'error')
  }
}

// [功能方法] createProduct (建立product)
// 為什麼要這麼做：初始化一個全新資源或訂單物件，為使用者接下來的編輯與提交做好準備。
async function createProduct() {
  const priceVal = productForm.value.price === "" || productForm.value.price === null ? 0 : Number(productForm.value.price)
  const stockVal = productForm.value.stock === "" || productForm.value.stock === null ? 9999 : Number(productForm.value.stock)
  const sessionsVal = productForm.value.sessionsPerUnit === "" || productForm.value.sessionsPerUnit === null ? 1 : Number(productForm.value.sessionsPerUnit)

  const payload = {
    name: productForm.value.name,
    type: productForm.value.type,
    price: priceVal,
    stock: stockVal,
    description: productForm.value.description || '',
    imageUrl: productForm.value.imageUrl || '',
    productCategoryId: null, // 課程不需要綁商品分類
    classSize: productForm.value.classSize || '',
    isTrial: productForm.value.isTrial === true,
    sessionsPerUnit: sessionsVal
  }

  try {
    await api.post('/api/employee/products', payload)
    showToast('新課程方案上架成功！')
    showAddProduct.value = false
    productForm.value = { name: '', type: 'COURSE', price: null, stock: 9999, description: '', imageUrl: '', productCategoryId: null, classSize: '', isTrial: false, sessionsPerUnit: 1 }
    await loadProducts()
  } catch (e) {
    showToast(e.response?.data?.message || '新增課程失敗', 'error')
  }
}

// [功能方法] openEditModal (開啟編輯modal)
// 為什麼要這麼做：控制特定彈窗 (Modal) 或抽屜的顯示狀態，引導使用者進行下一步的核心操作。
function openEditModal(p) {
  editForm.value = {
    id: p.id,
    name: p.name,
    type: p.type,
    price: p.price,
    stock: p.stock || 9999,
    description: p.description || '',
    imageUrl: p.imageUrl || '',
    productCategoryId: null,
    classSize: p.classSize || '',
    isTrial: p.isTrial === true,
    sessionsPerUnit: p.sessionsPerUnit || 1
  }
  showEditProduct.value = true
}

// [功能方法] updateProduct (更新product)
// 為什麼要這麼做：將使用者修改後的資料同步至伺服器或本地 State，完成狀態更新並提供正確的介面呈現。
async function updateProduct() {
  try {
    await api.put(`/api/employee/products/${editForm.value.id}`, editForm.value)
    showToast('課程方案資料更新成功')
    showEditProduct.value = false
    await loadProducts()
  } catch (e) {
    showToast(e.response?.data?.message || '更新失敗', 'error')
  }
}

// [功能方法] deleteProduct (刪除product)
// 為什麼要這麼做：呼叫後端刪除 API 移除指定資料，並在完成後重新整理列表以確保畫面步調一致。
async function deleteProduct(id) {
  if (!confirm('確認將此課程方案下架？會員將無法再線上購買此方案。')) return
  try {
    await api.delete(`/api/employee/products/${id}`)
    showToast('該項目已成功下架')
    await loadProducts()
  } catch (e) {
    showToast(e.response?.data?.message || '下架失敗', 'error')
  }
}

// [功能方法] createCategory (建立category)
// 為什麼要這麼做：初始化一個全新資源或訂單物件，為使用者接下來的編輯與提交做好準備。
async function createCategory() {
  if (!newCategoryForm.value.name.trim()) return
  try {
    await api.post('/api/employee/course-categories', { name: newCategoryForm.value.name })
    showToast('課程分類新增成功')
    newCategoryForm.value.name = ''
    await loadCategories()
  } catch (e) {
    showToast(e.response?.data?.message || '新增分類失敗', 'error')
  }
}

// [功能方法] deleteCategory (刪除category)
// 為什麼要這麼做：呼叫後端刪除 API 移除指定資料，並在完成後重新整理列表以確保畫面步調一致。
async function deleteCategory(cat) {
  if (!confirm(`確認刪除課程分類「${cat.name}」？`)) return
  try {
    await api.delete(`/api/employee/course-categories/${cat.id}`)
    showToast('分類已刪除')
    await loadCategories()
  } catch (e) {
    showToast(e.response?.data?.message || '刪除分類失敗', 'error')
  }
}

// 團體課程 ERP 管理邏輯 (後端 Pageable 串接)
// [功能方法] loadGroupCourses (載入groupcourses)
// 為什麼要這麼做：向後端 API 請求最新數據，確保頁面載入或重新整理時，使用者能看到最即時且正確的資料。
async function loadGroupCourses() {
  try {
    const statusParam = gcActiveFilter.value === 'ALL' ? undefined : gcActiveFilter.value
    const res = await api.get('/api/employee/group-courses', {
      params: {
        page: gcCurrentPage.value - 1,
        size: gcItemsPerPage.value,
        keyword: gcFilterParams.value.keyword || undefined,
        status: statusParam,
        startDate: gcFilterParams.value.startDate || undefined,
        endDate: gcFilterParams.value.endDate || undefined
      }
    })
    const page = res.data
    groupCoursesList.value = page.content || []
    gcTotalPages.value = page.totalPages || 1
    gcTotalItems.value = page.totalElements || 0
  } catch (err) {
    console.error('Failed to load group courses:', err)
  }
}

// [功能方法] onGcSearch (當gc搜尋)
// 為什麼要這麼做：根據使用者輸入的關鍵字或篩選條件，重新過濾出符合條件的清單，提供快速檢索功能。
function onGcSearch() {
  gcCurrentPage.value = 1
  loadGroupCourses()
}

// [功能方法] resetGcFilters (重置gcfilters)
// 為什麼要這麼做：將所有篩選條件、分頁或表單狀態還原至預設初值，解決狀態殘留引起的查詢問題。
function resetGcFilters() {
  gcFilterParams.value.keyword = ''
  gcFilterParams.value.startDate = ''
  gcFilterParams.value.endDate = ''
  gcActiveFilter.value = 'ALL'
  gcCurrentPage.value = 1
  loadGroupCourses()
}

// 監聽分頁與狀態條件變化
// [監聽器] 監聽特定狀態變化
// 為什麼要這麼做：當監聽的數據或屬性發生異動時，即時觸發與其關聯的連動業務邏輯、重新計算數值或重新載入資料。
watch([gcCurrentPage, gcItemsPerPage, gcActiveFilter, () => gcFilterParams.value.startDate, () => gcFilterParams.value.endDate], () => {
  loadGroupCourses()
})


// [功能方法] loadTrainers (載入trainers)
// 為什麼要這麼做：向後端 API 請求最新數據，確保頁面載入或重新整理時，使用者能看到最即時且正確的資料。
async function loadTrainers() {
  try {
    const res = await api.get('/api/member/trainers')
    trainersList.value = res.data || []
  } catch (err) {
    console.error('Failed to load trainers:', err)
  }
}

// [功能方法] createGroupCourse (建立groupcourse)
// 為什麼要這麼做：初始化一個全新資源或訂單物件，為使用者接下來的編輯與提交做好準備。
async function createGroupCourse() {
  if (!groupCourseForm.value.name || !groupCourseForm.value.trainerId || !groupCourseForm.value.categoryId) {
    showToast('請填寫完整必填欄位', 'warning')
    return
  }
  saving.value = true
  try {
    await api.post('/api/employee/group-courses', groupCourseForm.value)
    showToast('團體課程新增上傳成功！')
    showAddGroupCourse.value = false
    groupCourseForm.value = {
      name: '',
      trainerId: null,
      maxCapacity: 20,
      categoryId: null,
      description: '',
      imageUrl: '',
      isTrial: false,
      status: 'ACTIVE'
    }
    await loadGroupCourses()
  } catch (e) {
    showToast(e.response?.data?.message || '新增失敗', 'error')
  } finally {
    saving.value = false
  }
}

// [功能方法] openEditGroupCourseModal (開啟編輯groupcoursemodal)
// 為什麼要這麼做：控制特定彈窗 (Modal) 或抽屜的顯示狀態，引導使用者進行下一步的核心操作。
function openEditGroupCourseModal(c) {
  editGroupCourseForm.value = {
    groupCourseId: c.groupCourseId,
    name: c.name,
    trainerId: c.trainer?.id || c.trainer?.trainerId || null,
    maxCapacity: c.maxCapacity,
    categoryId: c.category?.id || c.category?.courseCategoryId || null,
    description: c.description || '',
    imageUrl: c.imageUrl || '',
    isTrial: c.isTrial === true,
    status: c.status || 'ACTIVE'
  }
  showEditGroupCourse.value = true
}

// [功能方法] updateGroupCourse (更新groupcourse)
// 為什麼要這麼做：將使用者修改後的資料同步至伺服器或本地 State，完成狀態更新並提供正確的介面呈現。
async function updateGroupCourse() {
  saving.value = true
  try {
    await api.put(`/api/employee/group-courses/${editGroupCourseForm.value.groupCourseId}`, editGroupCourseForm.value)
    showToast('團體課程資料修改成功！')
    showEditGroupCourse.value = false
    await loadGroupCourses()
  } catch (e) {
    showToast(e.response?.data?.message || '修改失敗', 'error')
  } finally {
    saving.value = false
  }
}

// [功能方法] deleteGroupCourse (刪除groupcourse)
// 為什麼要這麼做：呼叫後端刪除 API 移除指定資料，並在完成後重新整理列表以確保畫面步調一致。
async function deleteGroupCourse(id) {
  if (!confirm('確認將此團體課程下架？下架後學員將無法排班此課程。')) return
  try {
    await api.delete(`/api/employee/group-courses/${id}`)
    showToast('課程下架成功')
    await loadGroupCourses()
  } catch (e) {
    showToast(e.response?.data?.message || '下架失敗', 'error')
  }
}

// [功能方法] loadProducts (載入products)
// 為什麼要這麼做：向後端 API 請求最新數據，確保頁面載入或重新整理時，使用者能看到最即時且正確的資料。
async function loadProducts() {
  loading.value = true
  try {
    const statusParam = activeTableFilter.value === 'ALL' ? undefined : activeTableFilter.value
    
    // 如果快速篩選是特定狀態 (比如 INACTIVE)，或是特定商品類型，我們可以做映射
    let mappedStatus = undefined
    let mappedType = undefined
    
    if (activeTableFilter.value === 'INACTIVE') {
      mappedStatus = 'INACTIVE'
    } else if (activeTableFilter.value === 'PRIVATE_COURSE') {
      mappedType = 'PRIVATE_COURSE'
      mappedStatus = 'ACTIVE'
    } else if (activeTableFilter.value === 'GROUP_COURSE') {
      mappedType = 'GROUP_COURSE'
      mappedStatus = 'ACTIVE'
    } else {
      mappedStatus = 'ACTIVE'
    }

    const res = await api.get('/api/employee/products', {
      params: {
        page: currentPage.value - 1,
        size: itemsPerPage.value,
        keyword: filterParams.value.keyword || undefined,
        status: mappedStatus,
        type: mappedType,
        startDate: filterParams.value.startDate || undefined,
        endDate: filterParams.value.endDate || undefined
      }
    })
    const page = res.data
    coursesList.value = (page.content || []).map(c => ({
      id: c.productId,
      name: c.name,
      description: c.description,
      imageUrl: c.imageUrl,
      price: c.price || 0,
      sessionsPerUnit: c.sessionsPerUnit || 1,
      classSize: c.classSize || '1 對 1',
      trainerName: c.categoryName || '',
      status: c.status === 'ACTIVE' ? 1 : 0,
      type: c.type || 'PRIVATE_COURSE',
      createdAt: c.createdAt || new Date().toISOString()
    }))
    totalPages.value = page.totalPages || 1
    totalItems.value = page.totalElements || 0
  } catch (err) {
    console.error('Failed to load courses:', err)
    showToast('加載課程資料失敗', 'error')
  } finally {
    loading.value = false
  }
}


// [功能方法] loadCategories (載入categories)
// 為什麼要這麼做：向後端 API 請求最新數據，確保頁面載入或重新整理時，使用者能看到最即時且正確的資料。
async function loadCategories() {
  try {
    const res = await api.get('/api/course-categories')
    courseCategories.value = (res.data || []).map(cat => ({
      id: cat.courseCategoryId || cat.id,
      name: cat.name
    }))
  } catch (err) {
    console.error('Failed to load categories:', err)
  }
}

// [生命週期掛載] 組件初始化載入
// 為什麼要這麼做：在 Vue 元件完全掛載至網頁 DOM 後，立即向後端發送請求載入初始頁面資料，提供即時的視覺展現。
onMounted(() => {
  loadProducts()
  loadCategories()
  loadGroupCourses()
  loadTrainers()
})
</script>

<style scoped>
.erp-tabs .tab-btn {
  background: none;
  border: none;
  padding: 0.75rem 1.5rem;
  font-size: 0.95rem;
  font-weight: 700;
  color: var(--text-secondary, #64748b);
  cursor: pointer;
  border-bottom: 3px solid transparent;
  transition: all 0.25s ease;
}
.erp-tabs .tab-btn:hover {
  color: var(--primary);
}
.erp-tabs .tab-btn.active {
  color: var(--primary);
  border-bottom-color: var(--primary);
}

.admin-table table td {
  vertical-align: middle;
}
.product-name-cell {
  text-align: center;
}
.product-name-cell .name-text {
  font-weight: 700;
  color: #0f172a;
  margin-bottom: 0.25rem;
  text-align: center;
}
.product-name-cell .desc-text {
  font-size: 0.72rem;
  color: #64748b;
  white-space: normal;
  word-break: break-word;
  text-align: center;
  line-height: 1.5;
  margin-top: 0.2rem;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.dashboard-footer-blank {
  height: 8rem;
}
.slide-enter-active,
.slide-leave-active {
  transition: all 0.3s ease-out;
}
.slide-enter-from,
.slide-leave-to {
  transform: translateY(-20px);
  opacity: 0;
}

/* Custom Checkbox 美化樣式 */
.checkbox-container {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  cursor: pointer;
  user-select: none;
  font-size: 0.82rem;
  font-weight: 600;
  color: var(--text-secondary);
}

.checkbox-container .custom-checkbox {
  position: absolute;
  opacity: 0;
  cursor: pointer;
  height: 0;
  width: 0;
}

.checkbox-container .checkmark {
  position: relative;
  display: inline-block;
  height: 18px;
  width: 18px;
  background-color: var(--bg-section);
  border: 1.5px solid var(--border);
  border-radius: 4px;
  transition: all 0.2s ease;
}

.checkbox-container:hover .custom-checkbox ~ .checkmark {
  border-color: var(--primary);
}

.checkbox-container .custom-checkbox:checked ~ .checkmark {
  background-color: var(--primary);
  border-color: var(--primary);
}

.checkbox-container .checkmark:after {
  content: "";
  position: absolute;
  display: none;
}

.checkbox-container .custom-checkbox:checked ~ .checkmark:after {
  display: block;
}

.checkbox-container .checkmark:after {
  left: 5px;
  top: 1.5px;
  width: 4px;
  height: 8px;
  border: solid white;
  border-width: 0 2px 2px 0;
  transform: rotate(45deg);
}

.checkbox-label-text {
  font-size: 0.82rem;
}

/* 共用分頁樣式 */
.total-count-text {
  font-size: 0.88rem;
  color: var(--text-secondary, #64748b);
  font-weight: 600;
}
.page-size-selector {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.88rem;
  color: var(--text-secondary, #64748b);
}
.select-sm {
  padding: 0.3rem 1.5rem 0.3rem 0.6rem;
  font-size: 0.85rem;
  border-radius: 6px;
  border: 1px solid var(--border, #e2e8f0);
  background-color: var(--bg-card, #ffffff);
  color: var(--text-primary);
  cursor: pointer;
}
.pagination .page-btn {
  width: 28px !important;
  height: 28px !important;
  font-size: 0.8rem !important;
  border-radius: 4px !important;
}

/* 固定表格欄寬、防止分頁時表格跳動 */
.product-table-wrap {
  overflow-x: auto;
  min-height: 560px;     /* 外層固定最小高度，row 筆數少時不縮水 */
}
.admin-table table {
  table-layout: fixed !important;   /* 強制固定欄寬，不隨內容伸縮 */
  width: 100% !important;
}
.admin-table table th,
.admin-table table td {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}
/* 允許折行與顯示完整文字的欄位 */
.product-name-cell,
.trainer-cell,
.desc-text {
  white-space: normal !important;
  word-break: break-word !important;
  overflow: visible !important;
}
.admin-table table tbody tr {
  height: 72px;          /* 每行固定高度 */
}
</style>



