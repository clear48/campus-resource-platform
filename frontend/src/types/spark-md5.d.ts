declare module 'spark-md5' {
  class ArrayBufferHasher {
    append(data: ArrayBuffer): void
    end(): string
    reset(): void
  }

  const SparkMD5: {
    ArrayBuffer: new () => ArrayBufferHasher
  }

  export default SparkMD5
}
