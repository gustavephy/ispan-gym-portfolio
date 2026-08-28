<template>
  <div class="page-container admin-dashboard">
    <span class="section-tag">ADMIN</span>
    <h1 class="section-title">課程方案管理</h1>
    <p class="section-desc">管理團體課程主檔與私教課程方案，包含新增、編輯、上下架操作。</p>

    <!-- ERP 頂部標籤頁 -->
    <div class="erp-tabs" style="display: flex; gap: 1.5rem; border-bottom: 1.5px solid var(--border); margin-bottom: 2rem;">
      <button class="tab-btn" :class="{ active: currentTab === 'groupCourses' }" @click="currentTab = 'groupCourses'">
        團體課程管理
      </button>
      <button class="tab-btn" :class="{ active: currentTab === 'privateCourses' }" @click="currentTab = 'privateCourses'">
        私教課程管理
      </button>
    </div>

    <!-- TAB 1: 團體課程管理 (CRUD) -->
    <div v-if="currentTab === 'groupCourses'">
      <div class="page-header-row" style="display: flex; gap: 1rem; margin-bottom: 1.5rem;">
        <button class="btn btn-primary btn-sm" @click="showAddGroupCourse = !showAddGroupCourse">
          <span>{{ showAddGroupCourse ? '✕ 取消新增' : '＋ 新增團體課程' }}</span>
        </button>
        <button class="btn btn-outline btn-sm" @click="showCategoryManager = true">
          <span>⚙ 管理課程分類</span>
        </button>
      </div>

      <!-- 新增團體課程表單 -->
      <Transition name="slide">
        <AdminFormCard
          v-if="showAddGroupCourse"
          title="新增團體課程資料"
          helper="*請確實填寫指導教練與課程資訊"
          @submit="createGroupCourse"
        >
          <!-- 課程名稱跳滿 3 欄 -->
          <div class="form-group" style="grid-column: 1 / -1;">
            <label class="form-label">課程名稱</label>
            <input v-model="groupCourseForm.name" class="form-input" placeholder="例如: 燃脂有氧、基礎空中瑜珈" required />
          </div>
          <!-- 指導教練占 2 欄，上限人數占 1 欄 -->
          <div class="form-group" style="grid-column: span 2;">
            <label class="form-label">指導教練</label>
            <select v-model="groupCourseForm.trainerId" class="form-input" required>
              <option :value="null" disabled>請選擇指導教練</option>
              <option v-for="t in trainersList" :key="t.trainerId" :value="t.trainerId">
                {{ t.displayName }} ({{ t.specialty }})
              </option>
            </select>
          </div>
          <div class="form-group">
            <label class="form-label">上限人數</label>
            <input v-model.number="groupCourseForm.maxCapacity" type="number" class="form-input" min="1" max="100" required />
          </div>
          <!-- 課程分類 + 免費體驗 占 3 欄 -->
          <div class="form-group" style="grid-column: 1 / -1;">
            <label class="form-label">課程分類</label>
            <div style="display:flex; align-items:center; gap: 0.75rem;">
              <select v-model="groupCourseForm.categoryId" class="form-input" required style="flex:1; max-width: 240px;">
                <option :value="null" disabled>請選擇分類</option>
                <option v-for="cat in courseCategories" :key="cat.id" :value="cat.id">
                  {{ cat.name }}
                </option>
              </select>
              <label class="checkbox-container" style="white-space:nowrap; flex-shrink:0;">
                <input v-model="groupCourseForm.isTrial" type="checkbox" class="custom-checkbox" />
                <span class="checkmark"></span>
                <span class="checkbox-label-text">免費體驗</span>
              </label>
            </div>
          </div>

          <template #extra>
            <div class="form-group file-upload-group" style="margin-top: 1rem; width: 100%;">
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
            <div class="form-group" style="margin-top: 1rem; width: 100%;">
              <label class="form-label">詳細說明介紹</label>
              <textarea v-model="groupCourseForm.description" class="form-input" rows="4" placeholder="請填寫課程特色、難易度、注意事項..." style="width: 100%;"></textarea>
            </div>
          </template>

          <template #actions>
            <button type="submit" class="btn btn-primary" :disabled="saving">確認新增上傳</button>
          </template>
        </AdminFormCard>
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
        <!-- 筆數與顯示數量控制列 (包含上方分頁) -->
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

          <!-- 上方分頁按鈕 -->
          <div class="pagination" v-if="gcTotalPages > 1" style="margin: 0;">
            <button type="button" class="page-btn" :disabled="gcCurrentPage === 1" @click="gcCurrentPage--">‹</button>
            <template v-for="n in gcVisiblePages" :key="n">
              <span v-if="n === '...'" class="page-ellipsis">...</span>
              <button v-else type="button" :class="['page-btn', { active: gcCurrentPage === n }]" @click="gcCurrentPage = n">{{ n }}</button>
            </template>
            <button type="button" class="page-btn" :disabled="gcCurrentPage === gcTotalPages" @click="gcCurrentPage++">›</button>
          </div>
        </div>

        <div class="product-table-wrap">
          <table style="table-layout: fixed; width: 100%;">
            <thead>
              <tr>
                <th style="width: 80px;">ID</th>
                <th style="width: 100px;">圖片</th>
                <th style="width: 300px;">課程名稱 &amp; 說明</th>
                <th style="width: 150px;">指導教練</th>
                <th style="width: 160px;">分類 & 人數</th>
                <th style="width: 100px;">免費體驗</th>
                <th style="width: 100px;">狀態</th>
                <th style="width: 150px;">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in rawGroupCourses" :key="c.id">
                <td>#{{ c.id }}</td>
                <td>
                  <img :src="c.imageUrl ? formatImageUrl(c.imageUrl) : DEFAULT_COURSE_IMAGE" class="product-thumb" alt="Course Image"
                       @error="useFallbackImage($event, DEFAULT_COURSE_IMAGE)" />
                </td>
                <td class="product-name-cell">
                  <div class="name-text">{{ c.name }}</div>
                  <div class="desc-text" :title="c.description">{{ c.description || '無詳細說明' }}</div>
                </td>
                <td class="trainer-cell">
                  <div style="font-weight: 600; color: var(--primary-dark);">{{ c.trainerName || '未分配' }}</div>
                </td>
                <td style="font-weight: 600;">
                  <span class="badge badge-primary" style="display:block; margin-bottom:4px;">{{ c.courseCategoryName || '未分類' }}</span>
                  <span style="font-size:0.82rem; color: var(--text-muted);">上限 {{ c.maxCapacity }} 人</span>
                </td>
                <td>
                  <span :class="['badge', c.isTrial ? 'badge-success' : 'badge-danger']">
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
                    <button class="btn btn-edit edit-btn-style btn-sm" @click="openEditGroupCourseModal(c)">編輯</button>
                    <button v-if="c.status === 'ACTIVE'" class="btn btn-danger deactive-btn-style btn-sm" @click="deleteGroupCourse(c.id)">下架</button>
                    <button v-if="c.status === 'INACTIVE'" class="btn btn-edit edit-btn-style btn-sm" @click="activateGroupCourse(c.id)">上架</button>
                  </div>
                </td>
              </tr>
              <tr v-if="rawGroupCourses.length === 0">
                <td colspan="8" style="text-align: center; padding: 3rem; color: #94a3b8;">
                  查無任何團體課程項目。
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 下方分頁按鈕 -->
        <div v-if="gcTotalPages > 1" style="padding: 1rem; display: flex; justify-content: center; border-top: 1px solid #f1f5f9;">
          <div class="pagination" style="margin: 0;">
            <button type="button" class="page-btn" :disabled="gcCurrentPage === 1" @click="gcCurrentPage--">‹</button>
            <template v-for="n in gcVisiblePages" :key="n">
              <span v-if="n === '...'" class="page-ellipsis">...</span>
              <button v-else type="button" :class="['page-btn', { active: gcCurrentPage === n }]" @click="gcCurrentPage = n">{{ n }}</button>
            </template>
            <button type="button" class="page-btn" :disabled="gcCurrentPage === gcTotalPages" @click="gcCurrentPage++">›</button>
          </div>
        </div>
      </div>
    </div>

    <!-- TAB 2: 私教課程管理 (唯讀 / 下架) -->
    <div v-else-if="currentTab === 'privateCourses'">
      <div class="page-header-row" style="display: flex; gap: 1rem; margin-bottom: 1.5rem;">
        <button class="btn btn-primary btn-sm" @click="showAddPrivateCourse = !showAddPrivateCourse">
          <span>{{ showAddPrivateCourse ? '✕ 取消新增' : '＋ 新增私教課程' }}</span>
        </button>
      </div>

      <!-- 新增私教課程表單 -->
      <Transition name="slide">
        <AdminFormCard
          v-if="showAddPrivateCourse"
          title="新增私教課程資料"
          helper="*請確實填寫指導教練與課程資訊"
          @submit="createPrivateCourse"
        >
          <!-- 課程名稱跳滿 3 欄 -->
          <div class="form-group" style="grid-column: 1 / -1;">
            <label class="form-label">課程名稱</label>
            <input v-model="privateCourseForm.name" class="form-input" placeholder="例如: 1對1私人教練體驗課" required />
          </div>
          <!-- 指導教練跳滿 3 欄 -->
          <div class="form-group" style="grid-column: 1 / -1;">
            <label class="form-label">指導教練</label>
            <select v-model="privateCourseForm.trainerId" class="form-input" required>
              <option :value="null" disabled>請選擇指導教練</option>
              <option v-for="t in trainersList" :key="t.trainerId" :value="t.trainerId">
                {{ t.displayName }} ({{ t.specialty }})
              </option>
            </select>
          </div>
          <!-- 堂數 / 售價 / 上限 各占 1 欄，一排三格 -->
          <div class="form-group">
            <label class="form-label">課程堂數</label>
            <input v-model.number="privateCourseForm.sessionPerUnit" type="number" class="form-input" min="1" required />
          </div>
          <div class="form-group">
            <label class="form-label">課程售價</label>
            <input v-model.number="privateCourseForm.price" type="number" class="form-input" min="0" required />
          </div>
          <div class="form-group">
            <label class="form-label">上限人數</label>
            <input v-model.number="privateCourseForm.maxCapacity" type="number" class="form-input" min="1" required />
          </div>
          <!-- 課程分類 + 免費體驗 占滿 3 欄 -->
          <div class="form-group" style="grid-column: 1 / -1;">
            <label class="form-label">課程分類</label>
            <div style="display:flex; align-items:center; gap: 0.75rem;">
              <select v-model="privateCourseForm.courseCategoryId" class="form-input" required style="flex:1; max-width: 240px;">
                <option :value="null" disabled>請選擇分類</option>
                <option v-for="cat in privateCourseCategories" :key="cat.id" :value="cat.id">
                  {{ cat.name }}
                </option>
              </select>
              <label class="checkbox-container" style="white-space:nowrap; flex-shrink:0;">
                <input v-model="privateCourseForm.isTrial" type="checkbox" class="custom-checkbox" />
                <span class="checkmark"></span>
                <span class="checkbox-label-text">免費體驗</span>
              </label>
            </div>
          </div>

          <template #extra>
            <div class="form-group file-upload-group" style="margin-top: 1rem; width: 100%;">
              <label class="form-label">課程照片</label>
              <div class="file-upload-wrapper">
                <label class="custom-file-btn">
                  上傳照片
                  <input type="file" @change="e => uploadFile(e, 'addPrivateCourse')" accept="image/*" class="hidden-file-input" />
                </label>
                <span v-if="uploadingFile" class="loading-spinner-inline"></span>
                <img v-if="privateCourseForm.imageUrl" :src="formatImageUrl(privateCourseForm.imageUrl)" class="form-preview-thumb" alt="Preview" />
              </div>
              <input v-model="privateCourseForm.imageUrl" class="form-input url-input" placeholder="圖片位址 URL" />
            </div>
            <div class="form-group" style="margin-top: 1rem; width: 100%;">
              <label class="form-label">詳細說明</label>
              <textarea v-model="privateCourseForm.description" class="form-input" rows="4" placeholder="填寫課程特色、適合對象等說明" style="width: 100%;"></textarea>
            </div>
          </template>

          <template #actions>
            <button type="submit" class="btn btn-primary" :disabled="saving">確認新增上傳</button>
          </template>
        </AdminFormCard>
      </Transition>

      <!-- 統一搜尋與篩選列 -->
      <SearchFilter
        v-model="pcFilterParams"
        keyword-label="私教課程搜尋"
        keyword-placeholder="搜尋課程名稱、教練姓名..."
        date-label="上架日期範圍"
        @search="onPcSearch"
        @reset="resetPcFilters"
      />

      <!-- 私教課程列表 -->
      <div class="admin-table card">
        <!-- 筆數與顯示數量控制列 (包含上方分頁) -->
        <div class="list-control-header bg-none" style="margin: 1rem 1rem 1.5rem 1rem; display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 1rem;">
          <div style="display: flex; align-items: center; gap: 1.5rem; flex-wrap: wrap;">
            <span class="total-count-text">共 {{ pcTotalItems }} 筆課程</span>
            <div class="page-size-selector">
              <label>顯示數量：</label>
              <select v-model.number="pcItemsPerPage" class="select-sm" @change="pcCurrentPage = 1">
                <option :value="5">5 筆</option>
                <option :value="10">10 筆</option>
                <option :value="20">20 筆</option>
              </select>
            </div>
          </div>

          <!-- 上方分頁按鈕 -->
          <div class="pagination" v-if="pcTotalPages > 1" style="margin: 0;">
            <button type="button" class="page-btn" :disabled="pcCurrentPage === 1" @click="pcCurrentPage--">‹</button>
            <template v-for="n in pcVisiblePages" :key="n">
              <span v-if="n === '...'" class="page-ellipsis">...</span>
              <button v-else type="button" :class="['page-btn', { active: pcCurrentPage === n }]" @click="pcCurrentPage = n">{{ n }}</button>
            </template>
            <button type="button" class="page-btn" :disabled="pcCurrentPage === pcTotalPages" @click="pcCurrentPage++">›</button>
          </div>
        </div>

        <div class="product-table-wrap">
          <table style="table-layout: fixed; width: 100%;">
            <thead>
              <tr>
                <th style="width: 80px;">ID</th>
                <th style="width: 100px;">圖片</th>
                <th style="width: 300px;">課程名稱 &amp; 說明</th>
                <th style="width: 150px;">指導教練</th>
                <th style="width: 120px;">堂數 & 人數</th>
                <th style="width: 120px;">定價金額</th>
                <th style="width: 100px;">狀態</th>
                <th style="width: 150px;">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in paginatedPrivateCourses" :key="c.id || c.privateCourseId">
                <td>#{{ c.id || c.privateCourseId }}</td>
                <td>
                  <img :src="c.imageUrl ? formatImageUrl(c.imageUrl) : DEFAULT_COURSE_IMAGE" class="product-thumb" alt="Course Image"
                       @error="useFallbackImage($event, DEFAULT_COURSE_IMAGE)" />
                </td>
                <td class="product-name-cell">
                  <div class="name-text">{{ c.name }}</div>
                  <div class="desc-text" :title="c.description">{{ c.description || '無詳細說明' }}</div>
                </td>
                <td class="trainer-cell">
                  <div style="font-weight: 600; color: var(--primary-dark);">{{ c.trainerName || c.trainer?.name || '專屬教練' }}</div>
                </td>
                <td>
                  <span style="font-weight: 600;">{{ c.sessionPerUnit || c.sessionsPerUnit || 1 }} 堂</span><br>
                  <span style="font-size:0.82rem; color: var(--text-muted);">上限 {{ c.maxCapacity || 1 }} 人</span>
                </td>
                <td style="font-weight: 700; color: var(--accent);">NT$ {{ Number(c.price || 0).toLocaleString() }}</td>
                <td>
                  <div class="status-cell-wrap">
                    <span :class="['status-dot', c.status === 'ACTIVE' || c.status === undefined ? 'active' : 'inactive']"></span>
                    <span :class="['status-text-label', c.status === 'ACTIVE' || c.status === undefined ? 'text-active' : 'text-inactive']">
                      {{ c.status === 'ACTIVE' || c.status === undefined ? '上架中' : '已下架' }}
                    </span>
                  </div>
                </td>
                <td>
                  <div class="action-buttons-wrap">
                    <button class="btn btn-edit edit-btn-style btn-sm" @click="openEditPrivateCourseModal(c)">編輯</button>
                    <button v-if="c.status === 'ACTIVE' || c.status === undefined" class="btn btn-danger deactive-btn-style btn-sm" @click="deactivatePrivateCourse(c.id || c.privateCourseId)">下架</button>
                    <button v-if="c.status === 'INACTIVE'" class="btn btn-edit edit-btn-style btn-sm" @click="activatePrivateCourse(c.id || c.privateCourseId)">上架</button>
                  </div>
                </td>
              </tr>
              <tr v-if="filteredPrivateCourses.length === 0">
                <td colspan="8" style="text-align: center; padding: 3rem; color: #94a3b8;">
                  目前沒有符合條件的私教課程。
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 下方分頁按鈕 -->
        <div v-if="pcTotalPages > 1" style="padding: 1rem; display: flex; justify-content: center; border-top: 1px solid #f1f5f9;">
          <div class="pagination" style="margin: 0;">
            <button type="button" class="page-btn" :disabled="pcCurrentPage === 1" @click="pcCurrentPage--">‹</button>
            <template v-for="n in pcVisiblePages" :key="n">
              <span v-if="n === '...'" class="page-ellipsis">...</span>
              <button v-else type="button" :class="['page-btn', { active: pcCurrentPage === n }]" @click="pcCurrentPage = n">{{ n }}</button>
            </template>
            <button type="button" class="page-btn" :disabled="pcCurrentPage === pcTotalPages" @click="pcCurrentPage++">›</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 編輯團體課程 Modal -->
    <AppModal v-model:visible="showEditGroupCourse" title="編輯團體課程內容" size="md">
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
              <option v-for="t in trainersList" :key="t.trainerId" :value="t.trainerId">
                {{ t.displayName }} ({{ t.specialty }})
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
          <div class="form-group checkbox-group" style="display:flex; align-items:center; justify-content:center; min-height: 38px;">
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
          <textarea v-model="editGroupCourseForm.description" class="form-input" rows="3" style="width: 100%; padding-top: 1rem;"></textarea>
        </div>
      </form>
      <template #footer>
        <button type="button" class="btn btn-ghost" @click="showEditGroupCourse = false">取消</button>
        <button type="submit" form="editGroupCourseForm" class="btn btn-primary" :disabled="saving">保存修改資料</button>
      </template>
    </AppModal>

    <!-- 編輯私教課程 Modal -->
    <AppModal v-model:visible="showEditPrivateCourse" title="編輯私教課程內容" size="md">
      <form id="editPrivateCourseForm" @submit.prevent="updatePrivateCourse">
        <div class="form-group">
          <label class="form-label">課程名稱</label>
          <input v-model="editPrivateCourseForm.name" class="form-input" required />
        </div>
        <div class="form-group" style="margin-top: 1rem;">
          <label class="form-label">課程堂數 (Session Per Unit)</label>
          <input v-model.number="editPrivateCourseForm.sessionPerUnit" type="number" class="form-input" min="1" required />
        </div>
        <div class="form-group" style="margin-top: 1rem;">
          <label class="form-label">課程售價</label>
          <input v-model.number="editPrivateCourseForm.price" type="number" class="form-input" min="0" required />
        </div>
        <div class="form-group" style="margin-top: 1rem;">
          <label class="form-label">上限人數</label>
          <input v-model.number="editPrivateCourseForm.maxCapacity" type="number" class="form-input" min="1" required />
        </div>
        <div class="form-group" style="margin-top: 1rem;">
          <label class="form-label">課程分類</label>
          <select v-model="editPrivateCourseForm.courseCategoryId" class="form-input" required>
            <option :value="null" disabled>請選擇分類</option>
            <option v-for="cat in privateCourseCategories" :key="cat.id" :value="cat.id">
              {{ cat.name }}
            </option>
          </select>
        </div>
        <div class="form-group" style="margin-top: 1rem;">
          <label class="form-label">指導教練</label>
          <select v-model="editPrivateCourseForm.trainerId" class="form-input" required>
            <option :value="null" disabled>請選擇指導教練</option>
            <option v-for="t in trainersList" :key="t.trainerId" :value="t.trainerId">
              {{ t.displayName }} ({{ t.specialty }})
            </option>
          </select>
        </div>
        <div class="form-group" style="margin-top: 1rem;">
          <label class="form-label">照片設定</label>
          <div class="file-upload-wrapper">
            <label class="custom-file-btn">
              重新上傳
              <input type="file" @change="e => uploadFile(e, 'editPrivateCourse')" accept="image/*" class="hidden-file-input" />
            </label>
            <span v-if="uploadingFile" class="loading-spinner-inline"></span>
            <img v-if="editPrivateCourseForm.imageUrl" :src="formatImageUrl(editPrivateCourseForm.imageUrl)" class="form-preview-thumb" alt="Preview" />
          </div>
          <input v-model="editPrivateCourseForm.imageUrl" class="form-input url-input" placeholder="圖片位址 URL" />
        </div>
        <div class="form-group" style="margin-top: 1rem;">
          <label class="form-label">詳細說明</label>
          <textarea v-model="editPrivateCourseForm.description" class="form-input" rows="3" style="width: 100%; padding-top: 1rem;"></textarea>
        </div>
        <div class="form-group checkbox-group" style="display:flex; align-items:center; justify-content:center; min-height: 38px; margin-top: 1rem;">
          <label class="checkbox-container">
            <input v-model="editPrivateCourseForm.isTrial" type="checkbox" class="custom-checkbox" />
            <span class="checkmark"></span>
            <span class="checkbox-label-text">開放免費體驗</span>
          </label>
        </div>
      </form>
      <template #footer>
        <button type="button" class="btn btn-ghost" @click="showEditPrivateCourse = false">取消</button>
        <button type="submit" form="editPrivateCourseForm" class="btn btn-primary" :disabled="saving">保存修改資料</button>
      </template>
    </AppModal>

    <!-- 分類管理 Modal -->
    <AppModal v-model:visible="showCategoryManager" title="管理課程分類" size="md">
      <div class="category-manager-content">
        <!-- 新增分類表單 -->
        <form @submit.prevent="createCategory" class="card" style="padding: 1rem; margin-bottom: 1.5rem; background: var(--bg-soft);">
          <h5 style="margin-bottom: 0.75rem; font-weight: 700;">新增分類</h5>
          <div style="display: flex; gap: 0.5rem; flex-wrap: wrap;">
            <input v-model="newCategoryForm.name" class="form-input" style="flex: 2; min-width: 150px;" placeholder="分類名稱，例如：瑜珈" required />
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
import { ref, computed, onMounted, inject, watch, onUnmounted } from 'vue'
import AppModal from '@/components/AppModal.vue'
import SearchFilter from '@/components/SearchFilter.vue'
import CategoryFilter from '@/components/CategoryFilter.vue'
import AdminFormCard from '@/components/AdminFormCard.vue'
import { formatImageUrl, useFallbackImage, DEFAULT_COURSE_IMAGE } from '@/utils/image.js'
import api from '@/api/axios.instance.js'
import { notify } from '@/api/error-bus.js'

const showToastInject = inject('showToast', null)
const showToast = (msg, type = 'success') => {
  if (showToastInject) {
    showToastInject(msg, type)
  } else {
    notify(type === 'error' ? '系統錯誤' : '成功', msg, type)
  }
}

const currentTab = ref('groupCourses')

// --- 團體課程管理狀態 (Tab 1) ---
const rawGroupCourses = ref([])
const showAddGroupCourse = ref(false)
const showEditGroupCourse = ref(false)
const showCategoryManager = ref(false)
const saving = ref(false)
const uploadingFile = ref(false)

const gcCurrentPage = ref(1)
const gcItemsPerPage = ref(10)
const gcTotalItems = ref(0)
const gcTotalPages = ref(1)

const gcFilterParams = ref({
  keyword: '',
  startDate: '',
  endDate: ''
})
const gcActiveFilter = ref('ALL')

const gcCategoryFilters = [
  { value: 'ALL',      label: '全部課程' },
  { value: 'ACTIVE',   label: '上架中' },
  { value: 'INACTIVE', label: '已下架' }
]

const trainersList = ref([])
const courseCategories = ref([])
const privateCourseCategories = computed(() => courseCategories.value.filter(cat => cat.name === '重量訓練'))

watch(privateCourseCategories, (newVal) => {
  if (newVal.length > 0) {
    if (!privateCourseForm.value.courseCategoryId) {
      privateCourseForm.value.courseCategoryId = newVal[0].id
    }
  }
})

const groupCourseForm = ref({
  name: '',
  trainerId: null,
  maxCapacity: 20,
  categoryId: null,
  description: '',
  imageUrl: '',
  isTrial: false
})

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

// --- 私教課程管理狀態 (Tab 2) ---
const rawPrivateCourses = ref([])
const pcCurrentPage = ref(1)
const pcItemsPerPage = ref(10)

const pcFilterParams = ref({
  keyword: '',
  startDate: '',
  endDate: ''
})

const showAddPrivateCourse = ref(false)
const showEditPrivateCourse = ref(false)

const privateCourseForm = ref({
  name: '',
  trainerId: null,
  maxCapacity: 1,
  sessionPerUnit: 1,
  price: 0,
  description: '',
  imageUrl: '',
  isTrial: false,
  courseCategoryId: null
})

const editPrivateCourseForm = ref({
  privateCourseId: null,
  name: '',
  maxCapacity: 1,
  sessionPerUnit: 1,
  price: 0,
  description: '',
  imageUrl: '',
  isTrial: false,
  courseCategoryId: null
})

// 課程分類管理新增表單
const newCategoryForm = ref({ name: '' })

// 監聽 Tab 切換
watch(currentTab, () => {
  if (currentTab.value === 'groupCourses') {
    gcCurrentPage.value = 1
    loadGroupCourses()
  } else {
    pcCurrentPage.value = 1
    loadPrivateCourses()
  }
})

// 團體課程後端已處理過濾，前端直接取得結果
// --- 團體課程後端分頁監聽 ---
watch([gcActiveFilter, gcCurrentPage, gcItemsPerPage], () => {
  loadGroupCourses()
})

let gcSearchTimer = null
watch(() => gcFilterParams.value.keyword, () => {
  clearTimeout(gcSearchTimer)
  gcSearchTimer = setTimeout(() => {
    gcCurrentPage.value = 1
    loadGroupCourses()
  }, 400)
})

// --- 私教課程過濾監聽 (前端過濾與分頁) ---
const filteredPrivateCourses = computed(() => {
  let list = rawPrivateCourses.value

  // 關鍵字篩選
  if (pcFilterParams.value.keyword.trim()) {
    const kw = pcFilterParams.value.keyword.trim().toLowerCase()
    list = list.filter(c =>
      c.name?.toLowerCase().includes(kw) ||
      c.trainerName?.toLowerCase().includes(kw) ||
      c.trainer?.name?.toLowerCase().includes(kw)
    )
  }

  // 日期篩選
  if (pcFilterParams.value.startDate) {
    const sDate = new Date(pcFilterParams.value.startDate + 'T00:00:00')
    list = list.filter(c => !c.createdAt || new Date(c.createdAt) >= sDate)
  }
  if (pcFilterParams.value.endDate) {
    const eDate = new Date(pcFilterParams.value.endDate + 'T23:59:59')
    list = list.filter(c => !c.createdAt || new Date(c.createdAt) <= eDate)
  }

  return list
})

const pcTotalItems = computed(() => filteredPrivateCourses.value.length)
const pcTotalPages = computed(() => Math.ceil(pcTotalItems.value / pcItemsPerPage.value) || 1)

const paginatedPrivateCourses = computed(() => {
  const start = (pcCurrentPage.value - 1) * pcItemsPerPage.value
  const end = start + pcItemsPerPage.value
  return filteredPrivateCourses.value.slice(start, end)
})

const gcVisiblePages = computed(() => getVisiblePagesArray(gcCurrentPage.value, gcTotalPages.value))
const pcVisiblePages = computed(() => getVisiblePagesArray(pcCurrentPage.value, pcTotalPages.value))

function getVisiblePagesArray(current, total) {
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
}

function onPcSearch() {
  pcCurrentPage.value = 1
}

function resetPcFilters() {
  pcFilterParams.value = { keyword: '', startDate: '', endDate: '' }
  pcCurrentPage.value = 1
}

// --- 檔案上傳 ---
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
    const res = await api.post('/api/admin/products/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    const url = res.url || res.data?.url
    if (url) {
      if (mode === 'addGroupCourse') {
        groupCourseForm.value.imageUrl = url
      } else if (mode === 'editGroupCourse') {
        editGroupCourseForm.value.imageUrl = url
      } else if (mode === 'addPrivateCourse') {
        privateCourseForm.value.imageUrl = url
      } else if (mode === 'editPrivateCourse') {
        editPrivateCourseForm.value.imageUrl = url
      }
      showToast('圖片上傳成功')
    }
  } catch (err) {
    showToast('圖片上傳失敗', 'error')
  } finally {
    uploadingFile.value = false
  }
}

// --- 團體課程 API 串接（後端分頁） ---
async function loadGroupCourses() {
  try {
    const params = {
      page: gcCurrentPage.value - 1,   // 後端 0-indexed
      size: gcItemsPerPage.value
    }
    if (gcFilterParams.value.keyword.trim()) {
      params.search = gcFilterParams.value.keyword.trim()
    }
    if (gcActiveFilter.value !== 'ALL') {
      params.status = gcActiveFilter.value
    }
    const res = await api.get('/api/admin/group-courses', { params })
    const data = res.data
    rawGroupCourses.value = data.items || []
    gcTotalItems.value = data.totalItems ?? rawGroupCourses.value.length
    gcTotalPages.value = data.totalPages ?? 1
  } catch (err) {
    console.error('Failed to load group courses:', err)
  }
}

function onGcSearch() {
  gcCurrentPage.value = 1
  loadGroupCourses()
}

function resetGcFilters() {
  gcFilterParams.value = { keyword: '', startDate: '', endDate: '' }
  gcActiveFilter.value = 'ALL'
  gcCurrentPage.value = 1
  loadGroupCourses()
}

async function createGroupCourse() {
  if (!groupCourseForm.value.name || !groupCourseForm.value.trainerId || !groupCourseForm.value.categoryId) {
    showToast('請填寫完整必填欄位', 'warning')
    return
  }
  saving.value = true
  try {
    await api.post('/api/admin/group-courses', {
      name: groupCourseForm.value.name,
      trainerId: groupCourseForm.value.trainerId,
      maxCapacity: groupCourseForm.value.maxCapacity,
      courseCategoryId: groupCourseForm.value.categoryId,
      description: groupCourseForm.value.description,
      imageUrl: groupCourseForm.value.imageUrl || null,
      isTrial: groupCourseForm.value.isTrial
    })
    showToast('團體課程新增成功！')
    showAddGroupCourse.value = false
    groupCourseForm.value = {
      name: '',
      trainerId: null,
      maxCapacity: 20,
      categoryId: null,
      description: '',
      imageUrl: '',
      isTrial: false
    }
    await loadGroupCourses()
  } catch (e) {
    showToast(e.response?.data?.message || '新增失敗', 'error')
  } finally {
    saving.value = false
  }
}

function openEditGroupCourseModal(c) {
  editGroupCourseForm.value = {
    groupCourseId: c.id || c.groupCourseId,
    name: c.name,
    trainerId: c.trainerId ? Number(c.trainerId) : null,
    maxCapacity: c.maxCapacity,
    categoryId: c.courseCategoryId ? Number(c.courseCategoryId) : null,
    description: c.description || '',
    imageUrl: c.imageUrl || '',
    isTrial: c.isTrial === true,
    status: c.status || 'ACTIVE'
  }
  showEditGroupCourse.value = true
}

async function updateGroupCourse() {
  saving.value = true
  try {
    await api.put(`/api/admin/group-courses/${editGroupCourseForm.value.groupCourseId}`, {
      name: editGroupCourseForm.value.name,
      description: editGroupCourseForm.value.description,
      imageUrl: editGroupCourseForm.value.imageUrl || null,
      isTrial: editGroupCourseForm.value.isTrial,
      maxCapacity: editGroupCourseForm.value.maxCapacity,
      courseCategoryId: editGroupCourseForm.value.categoryId,
      status: editGroupCourseForm.value.status
    })
    showToast('團體課程資料修改成功！')
    showEditGroupCourse.value = false
    await loadGroupCourses()
  } catch (e) {
    showToast(e.response?.data?.message || '修改失敗', 'error')
  } finally {
    saving.value = false
  }
}

async function deleteGroupCourse(id) {
  if (!confirm('確認將此團體課程下架？下架後學員將無法排班此課程。')) return
  try {
    await api.delete(`/api/admin/group-courses/${id}`)
    showToast('課程下架成功')
    await loadGroupCourses()
  } catch (e) {
    showToast('下架失敗', 'error')
  }
}

async function activateGroupCourse(id) {
  try {
    await api.post(`/api/admin/group-courses/${id}/activate`)
    showToast('課程重新上架成功')
    await loadGroupCourses()
  } catch (e) {
    showToast('上架失敗', 'error')
  }
}

// --- 私教課程 API 串接 ---
async function loadPrivateCourses() {
  try {
    const res = await api.get('/api/admin/private-courses/paged', {
      params: { page: 0, size: 1000 }
    })
    rawPrivateCourses.value = res.data?.items || []
  } catch (err) {
    console.error('Failed to load private courses:', err)
  }
}

async function deactivatePrivateCourse(id) {
  if (!confirm('確認下架此私教課程？下架後會員將無法預約此課程。')) return
  try {
    await api.post(`/api/admin/private-courses/${id}/deactivate`)
    showToast('私教課程已下架')
    await loadPrivateCourses()
  } catch (e) {
    showToast('下架失敗', 'error')
  }
}

async function activatePrivateCourse(id) {
  try {
    await api.post(`/api/admin/private-courses/${id}/activate`)
    showToast('私教課程已重新上架')
    await loadPrivateCourses()
  } catch (e) {
    showToast('上架失敗', 'error')
  }
}

async function createPrivateCourse() {
  saving.value = true
  try {
    await api.post('/api/admin/private-courses', {
      name: privateCourseForm.value.name,
      trainerId: privateCourseForm.value.trainerId,
      maxCapacity: privateCourseForm.value.maxCapacity,
      sessionPerUnit: privateCourseForm.value.sessionPerUnit,
      price: privateCourseForm.value.price,
      description: privateCourseForm.value.description,
      imageUrl: privateCourseForm.value.imageUrl || null,
      isTrial: privateCourseForm.value.isTrial,
      courseCategoryId: privateCourseForm.value.courseCategoryId,
      status: 'ACTIVE'
    })
    showToast('私教課程新增成功！')
    showAddPrivateCourse.value = false
    // reset form
    privateCourseForm.value = {
      name: '',
      trainerId: null,
      maxCapacity: 1,
      sessionPerUnit: 1,
      price: 0,
      description: '',
      imageUrl: '',
      isTrial: false,
      courseCategoryId: null
    }
    await loadPrivateCourses()
  } catch (e) {
    showToast(e.response?.data?.message || '新增失敗', 'error')
  } finally {
    saving.value = false
  }
}

function openEditPrivateCourseModal(c) {
  editPrivateCourseForm.value = {
    privateCourseId: c.id || c.privateCourseId,
    name: c.name,
    maxCapacity: c.maxCapacity || 1,
    sessionPerUnit: c.sessionPerUnit || c.sessionsPerUnit || 1,
    price: c.price || 0,
    description: c.description || '',
    imageUrl: c.imageUrl || '',
    isTrial: c.isTrial === true,
    courseCategoryId: c.courseCategoryId ? Number(c.courseCategoryId) : null,
    trainerId: c.trainerId ? Number(c.trainerId) : (c.trainer?.id ? Number(c.trainer.id) : null)
  }
  showEditPrivateCourse.value = true
}

async function updatePrivateCourse() {
  saving.value = true
  try {
    await api.put(`/api/admin/private-courses/${editPrivateCourseForm.value.privateCourseId}`, {
      name: editPrivateCourseForm.value.name,
      description: editPrivateCourseForm.value.description,
      imageUrl: editPrivateCourseForm.value.imageUrl || null,
      isTrial: editPrivateCourseForm.value.isTrial,
      maxCapacity: editPrivateCourseForm.value.maxCapacity,
      sessionPerUnit: editPrivateCourseForm.value.sessionPerUnit,
      price: editPrivateCourseForm.value.price,
      courseCategoryId: editPrivateCourseForm.value.courseCategoryId,
      trainerId: editPrivateCourseForm.value.trainerId
    })
    showToast('私教課程資料修改成功！')
    showEditPrivateCourse.value = false
    await loadPrivateCourses()
  } catch (e) {
    showToast(e.response?.data?.message || '修改失敗', 'error')
  } finally {
    saving.value = false
  }
}

// --- 課程分類管理 ---
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

async function createCategory() {
  if (!newCategoryForm.value.name.trim()) return
  try {
    await api.post('/api/admin/course-categories', { name: newCategoryForm.value.name })
    showToast('課程分類新增成功')
    newCategoryForm.value.name = ''
    await loadCategories()
  } catch (e) {
    showToast('新增分類失敗', 'error')
  }
}

async function deleteCategory(cat) {
  if (!confirm(`確認刪除課程分類「${cat.name}」？`)) return
  try {
    await api.delete(`/api/admin/course-categories/${cat.id}`)
    showToast('分類已刪除')
    await loadCategories()
  } catch (e) {
    showToast('刪除分類失敗', 'error')
  }
}

async function loadTrainers() {
  try {
    const res = await api.get('/api/trainers')
    trainersList.value = res.data || []
  } catch (err) {
    console.error('Failed to load trainers:', err)
  }
}

onMounted(() => {
  loadGroupCourses()
  loadCategories()
  loadTrainers()
})

onUnmounted(() => {
  clearTimeout(gcSearchTimer)
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

.admin-table {
  border-radius: 16px;
  padding: 0;
  overflow: hidden;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.02);
  border: 1px solid #e2e8f0;
}
.admin-table table {
  width: 100%;
  border-collapse: collapse;
  table-layout: auto;
  word-break: break-word;
}
.admin-table table th {
  background: #f8fafc;
  color: #475569;
  font-weight: 700;
  padding: 0.5rem 0.75rem;
  text-align: center;
  border-bottom: 1px solid #e2e8f0;
  font-size: 0.78rem;
  letter-spacing: 0.3px;
  vertical-align: middle;
}
.admin-table table td {
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid #f1f5f9;
  color: #334155;
  font-size: 0.78rem;
  vertical-align: middle;
  text-align: center;
}
.admin-table table tr:hover td {
  background: #f8fafc;
}
.product-name-cell {
  text-align: center;
}
.product-name-cell .name-text {
  font-weight: 700;
  color: #0f172a;
  margin-bottom: 0.25rem;
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
  line-clamp: 2;
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

/* Custom Checkbox */
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

/* Pagination & List Control */
.list-control-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.25rem;
  padding: 0.5rem 1rem;
  background: var(--bg-section, #f8fafc);
  border-radius: var(--radius-md, 8px);
  border: 1px solid var(--border, #e2e8f0);
}
.list-control-header.bg-none {
  background: none;
  border: none;
  padding: 0;
  margin-bottom: 0;
}
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

.product-table-wrap {
  overflow-x: auto;
  min-height: 560px;
}
.admin-table table {
  table-layout: fixed !important;
  width: 100% !important;
}
.admin-table table th,
.admin-table table td {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}
.product-name-cell,
.trainer-cell,
.desc-text {
  white-space: normal !important;
  word-break: break-word !important;
  overflow: visible !important;
}
.admin-table table tbody tr {
  height: 72px;
}

/* ===== 分頁按鈕樣式補強 (高解析度/小尺寸復刻) ===== */
.pagination {
  display: flex;
  justify-content: center;
  gap: 0.5rem;
}

.page-btn {
  width: 28px !important;
  height: 28px !important;
  border: 1.5px solid var(--border, #e8e0d9);
  border-radius: 4px !important;
  background: var(--bg-section, #ffffff);
  color: var(--text-secondary, #6b5f58);
  cursor: pointer;
  transition: all 0.2s ease;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.8rem !important;
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.02);
}

.page-btn.active,
.page-btn:hover {
  background: var(--primary, #5a4a42) !important;
  color: #ffffff !important;
  border-color: transparent !important;
}

.page-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  background: var(--bg-section, #ffffff) !important;
  color: var(--text-secondary, #6b5f58) !important;
  border-color: var(--border, #e8e0d9) !important;
}

.page-ellipsis {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  color: var(--text-secondary, #6b5f58);
  font-size: 0.8rem;
}

/* --- 按鈕與表單全域樣式 --- */
:deep(.btn), .btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  height: 38px !important;
  padding: 0 1.25rem !important;
  border-radius: 9999px; /* radius-xl */
  border: 1.5px solid transparent;
  cursor: pointer;
  font-size: 0.85rem;
  font-weight: 600;
  transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
  letter-spacing: 0.03em;
  background-clip: padding-box;
  box-sizing: border-box !important;
}

:deep(.btn-primary), .btn-primary {
  background: var(--primary-dark, #3c302b) !important;
  color: #ffffff !important;
  border-color: rgba(255, 255, 255, 0.1) !important;
  box-shadow: none;
}

:deep(.btn-primary:hover), .btn-primary:hover {
  background: var(--primary, #5a4a42) !important;
  color: #ffffff !important;
  transform: translateY(-2px);
  box-shadow: 0 2px 8px rgba(60, 48, 43, 0.03);
}

:deep(.btn-outline), .btn-outline {
  background: transparent !important;
  border: 2px solid var(--primary-dark, #3c302b) !important;
  color: var(--primary-dark, #3c302b) !important;
}

:deep(.btn-outline:hover), .btn-outline:hover {
  background: var(--primary-dark, #3c302b) !important;
  color: #ffffff !important;
}

:deep(.btn-edit), .btn-edit, :deep(.edit-btn-style), .edit-btn-style {
  border: 1px solid var(--border, #e8e0d9) !important;
  background: var(--bg-section, #ffffff) !important;
  color: var(--text-secondary, #6b5f58) !important;
  font-weight: 600 !important;
}

:deep(.btn-edit:hover), .btn-edit:hover, :deep(.edit-btn-style:hover), .edit-btn-style:hover {
  background: var(--bg-soft, #f5efe9) !important;
  color: var(--primary-dark, #3c302b) !important;
}

:deep(.btn-danger), .btn-danger, :deep(.deactive-btn-style), .deactive-btn-style {
  border: 1px solid rgba(239, 68, 68, 0.2) !important;
  background: rgba(239, 68, 68, 0.02) !important;
  color: #c0392b !important;
  font-weight: 600 !important;
}

:deep(.btn-danger:hover), .btn-danger:hover, :deep(.deactive-btn-style:hover), .deactive-btn-style:hover {
  background: rgba(239, 68, 68, 0.08) !important;
  color: #962d22 !important;
}

/* 表單與輸入框 */
:deep(.form-group), .form-group {
  margin-bottom: 0.75rem;
}

:deep(.form-label), .form-label {
  display: block;
  margin-bottom: 0.3rem;
  color: var(--text-secondary, #6b5f58);
  font-size: 0.85rem;
  font-weight: 500;
}

:deep(.form-input), .form-input {
  width: 100%;
  height: 38px !important;
  padding: 0 0.75rem;
  background: var(--bg-section, #ffffff);
  border: 1.5px solid var(--border, #e8e0d9);
  border-radius: 8px; /* radius-sm */
  color: var(--text-primary, #2b2320);
  font-size: 0.88rem;
  transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
  box-sizing: border-box !important;
}

:deep(.form-input:focus), .form-input:focus {
  border-color: var(--primary-light, #5a4a42);
  box-shadow: 0 0 0 3px rgba(188, 163, 143, 0.15);
}

/* 額外輔助小尺寸按鈕樣式 */
:deep(.btn-sm), .btn-sm {
  height: 30px !important;
  padding: 0 0.8rem !important;
  font-size: 0.78rem !important;
}

/* ===== 新增表單區塊與上傳樣式 ===== */
.add-form-card {
  margin-bottom: 2rem;
  border: 1px solid var(--border, #e8e0d9) !important;
  background: #ffffff;
  padding: 1.75rem;
  border-radius: 16px;
  animation: slideDown 0.3s ease-out;
}
@keyframes slideDown {
  from { opacity: 0; transform: translateY(-10px); }
  to   { opacity: 1; transform: translateY(0); }
}
.form-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.25rem;
  border-bottom: 1px solid var(--border, #e8e0d9);
  padding-bottom: 0.75rem;
}
.form-header h4 {
  font-size: 1.05rem;
  color: var(--primary-dark, #3c302b);
  font-weight: 700;
  margin: 0;
}
.form-helper {
  font-size: 0.75rem;
  color: #ef4444;
}
.form-grid-responsive {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 1.25rem;
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 1.5rem;
}
.file-upload-wrapper {
  display: flex;
  align-items: center;
  gap: 1rem;
  margin-bottom: 0.75rem;
}
.custom-file-btn {
  background: var(--bg-soft, #f5efe9);
  border: 1px solid var(--border, #e8e0d9);
  color: var(--text-secondary, #6b5f58);
  padding: 0.5rem 1rem;
  border-radius: 8px;
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.25s ease;
}
.custom-file-btn:hover {
  background: var(--primary-light, #5a4a42);
  color: #ffffff;
}
.hidden-file-input {
  display: none;
}
.url-input {
  margin-top: 0.5rem;
}
.form-preview-thumb {
  width: 90px;
  height: 90px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid var(--border, #e8e0d9);
}

/* ===== 課程分類管理表格樣式 (復刻自 EmployeeLayout) ===== */
.category-manager-content table {
  width: 100%;
  border-collapse: collapse;
}
.category-manager-content table th {
  background: #f8fafc;
  color: #475569;
  font-weight: 700;
  padding: 0.6rem 0.5rem;
  border-bottom: 1.5px solid var(--border, #e8e0d9);
  font-size: 0.82rem;
}
.category-manager-content table td {
  padding: 0.6rem 0.5rem;
  border-bottom: 1px solid #f1f5f9;
  color: var(--text-primary, #2b2320);
  font-size: 0.85rem;
  vertical-align: middle;
}
.category-manager-content table tr:hover td {
  background: #f8fafc;
}
</style>