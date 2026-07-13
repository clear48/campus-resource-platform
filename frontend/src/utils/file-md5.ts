import SparkMD5 from 'spark-md5'

export type FileMd5ProgressCallback = (percent: number) => void

/**
 * 按分片读取文件计算 MD5，避免大文件一次性转换为字符串或载入超大内存块。
 */
export async function calculateFileMd5(
  file: File,
  onProgress?: FileMd5ProgressCallback,
  chunkSize = 2 * 1024 * 1024,
): Promise<string> {
  const hasher = new SparkMD5.ArrayBuffer()
  const totalChunks = Math.max(1, Math.ceil(file.size / chunkSize))

  for (let index = 0; index < totalChunks; index += 1) {
    const start = index * chunkSize
    const chunk = file.slice(start, Math.min(start + chunkSize, file.size))

    hasher.append(await chunk.arrayBuffer())
    onProgress?.(Math.round(((index + 1) / totalChunks) * 100))
  }

  return hasher.end()
}
