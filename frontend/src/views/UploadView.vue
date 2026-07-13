<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getCategories } from '../api/categories'
import { checkFileDuplicate, uploadFile } from '../api/files'
import { createResource } from '../api/resources'
import type { CategoryItem } from '../types/category'
import type { CreateResourceResult } from '../types/resource'
import { getResourceStatusLabel, getResourceTypeLabel } from '../types/enums'
import { calculateFileMd5 } from '../utils/file-md5'
import { ApiBusinessError } from '../utils/request'

const selectedFile = ref<File | null>(null)
const fileId = ref<number | null>(null)
const md5Progress = ref(0)
const uploadProgress = ref(0)
const processing = ref(false)
const errorMessage = ref('')
const resultMessage = ref('')
const categories = ref<CategoryItem[]>([])
const metadata = ref({ title: '', description: '', categoryId: undefined as number | undefined, courseName: '', resourceType: undefined as number | undefined, tags: '' })
const submitting = ref(false)
const submitError = ref('')
const createResult = ref<CreateResourceResult | null>(null)

/** 资料创建前的必填项由前端快速提示，后端仍负责最终参数和业务校验。 */
const canSubmitResource = computed(() => fileId.value !== null
  && metadata.value.title.trim() !== ''
  && metadata.value.categoryId !== undefined
  && metadata.value.courseName.trim() !== ''
  && metadata.value.resourceType !== undefined)

/** 选择文件后完成 MD5、秒传预检和必要的物理上传；本步不创建资料记录。 */
async function processSelectedFile(file: File) {
  selectedFile.value = file
  fileId.value = null
  md5Progress.value = 0
  uploadProgress.value = 0
  errorMessage.value = ''
  resultMessage.value = ''
  submitError.value = ''
  createResult.value = null
  processing.value = true

  try {
    const fileMd5 = await calculateFileMd5(file, (percent) => {
      md5Progress.value = percent
    })
    const checkResult = await checkFileDuplicate({ fileMd5, fileSize: file.size })

    if (checkResult.secondUpload && checkResult.fileId != null) {
      // 未命中时 fileId 可能是 null 或缺省，只有命中且 ID 有效才跳过上传。
      fileId.value = checkResult.fileId
      resultMessage.value = '已命中秒传，复用已有文件。'
      return
    }

    const uploadResult = await uploadFile(file, (percent) => {
      uploadProgress.value = percent
    })
    fileId.value = uploadResult.fileId
    uploadProgress.value = 100
    resultMessage.value = '文件上传成功，下一步可填写资料信息。'
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '文件处理失败'
  } finally {
    processing.value = false
  }
}

function handleFileChange(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]

  if (file) {
    void processSelectedFile(file)
  }
}

async function loadCategories() {
  categories.value = await getCategories({ parentId: 0 })
}

/** 将逗号输入转换为请求数组；后端还会继续执行去空白、去重和保序。 */
function normalizeTags(tags: string): string[] | undefined {
  const values = tags.split(',').map((item) => item.trim()).filter(Boolean)

  return values.length > 0 ? values : undefined
}

/** 复用文件 ID 创建业务资料，失败时不清空用户已经填写的表单。 */
async function submitResource() {
  if (!canSubmitResource.value || fileId.value === null || metadata.value.categoryId === undefined || metadata.value.resourceType === undefined) {
    submitError.value = '请先完成文件上传，并填写标题、分类、课程和资料类型。'
    return
  }

  submitting.value = true
  submitError.value = ''

  try {
    createResult.value = await createResource({
      fileId: fileId.value,
      title: metadata.value.title.trim(),
      description: metadata.value.description.trim() || undefined,
      categoryId: metadata.value.categoryId,
      courseName: metadata.value.courseName.trim(),
      resourceType: metadata.value.resourceType,
      tags: normalizeTags(metadata.value.tags),
    })
  } catch (error) {
    submitError.value = error instanceof ApiBusinessError || error instanceof Error ? error.message : '创建资料失败'
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  void loadCategories()
})
</script>

<template>
  <section class="upload-view">
    <el-card shadow="never">
      <template #header>上传文件</template>
      <p class="description">先计算 MD5 并预检秒传；本页当前步骤只生成可复用的文件 ID。</p>
      <input data-test="file-input" type="file" :disabled="processing" @change="handleFileChange" />

      <div v-if="selectedFile" class="upload-view__progress">
        <p>已选择：{{ selectedFile.name }}</p>
        <p>MD5 计算进度</p>
        <el-progress :percentage="md5Progress" />
        <p>上传进度</p>
        <el-progress :percentage="uploadProgress" />
      </div>

      <el-alert v-if="errorMessage" class="upload-view__alert" type="error" :title="errorMessage" :closable="false" show-icon />
      <el-alert v-if="resultMessage" class="upload-view__alert" type="success" :title="resultMessage" :closable="false" show-icon />
      <el-tag v-if="fileId" type="success">文件 ID：{{ fileId }}</el-tag>

      <el-divider>资料信息</el-divider>
      <el-form :model="metadata" label-position="top" @submit.prevent="submitResource">
        <el-form-item label="标题"><el-input v-model="metadata.title" data-test="metadata-title" /></el-form-item>
        <el-form-item label="简介"><el-input v-model="metadata.description" data-test="metadata-description" type="textarea" /></el-form-item>
        <el-row :gutter="16">
          <el-col :span="12"><el-form-item label="分类"><el-select v-model="metadata.categoryId" data-test="metadata-category" class="upload-view__control"><el-option v-for="item in categories" :key="item.categoryId" :label="item.categoryName" :value="item.categoryId" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="课程"><el-input v-model="metadata.courseName" data-test="metadata-course" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="类型"><el-select v-model="metadata.resourceType" data-test="metadata-type" class="upload-view__control"><el-option v-for="type in [1, 2, 3, 4, 5, 99]" :key="type" :label="getResourceTypeLabel(type)" :value="type" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="标签"><el-input v-model="metadata.tags" data-test="metadata-tags" placeholder="逗号分隔" /></el-form-item></el-col>
        </el-row>
        <el-alert v-if="submitError" class="upload-view__alert" type="error" :title="submitError" :closable="false" show-icon />
        <el-button data-test="metadata-submit" type="primary" :loading="submitting" :disabled="!canSubmitResource" native-type="submit">提交资料</el-button>
      </el-form>

      <el-result v-if="createResult" icon="success" title="资料已创建，等待管理员审核" :sub-title="`资料 ID：${createResult.resourceId}；状态：${getResourceStatusLabel(createResult.status)}`">
        <template #extra>
          <el-link data-test="my-uploads-link" href="/me/uploads" type="primary">查看我的上传</el-link>
        </template>
      </el-result>
    </el-card>
  </section>
</template>
