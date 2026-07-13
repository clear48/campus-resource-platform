<script setup lang="ts">
import { ref } from 'vue'
import { checkFileDuplicate, uploadFile } from '../api/files'
import { calculateFileMd5 } from '../utils/file-md5'

const selectedFile = ref<File | null>(null)
const fileId = ref<number | null>(null)
const md5Progress = ref(0)
const uploadProgress = ref(0)
const processing = ref(false)
const errorMessage = ref('')
const resultMessage = ref('')

/** 选择文件后完成 MD5、秒传预检和必要的物理上传；本步不创建资料记录。 */
async function processSelectedFile(file: File) {
  selectedFile.value = file
  fileId.value = null
  md5Progress.value = 0
  uploadProgress.value = 0
  errorMessage.value = ''
  resultMessage.value = ''
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
    </el-card>
  </section>
</template>
