import { http } from '@/utils/request'
import service from '@/utils/request'

export const fileApi = {
  /**
   * 上传（multipart），返回 {url, name, size}。
   * 动态壁纸视频体积可达 50MB，故单次上传超时放宽到 5 分钟，并透传进度回调
   * （默认 axios 超时 30s 对大视频不够用）。
   */
  upload: (file: File, onProgress?: (percent: number) => void) => {
    const form = new FormData()
    form.append('file', file)
    return service.post('/api/admin/files/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 300000,
      onUploadProgress: (e) => {
        if (onProgress && e.total) {
          onProgress(Math.round((e.loaded / e.total) * 100))
        }
      },
    })
  },
  list: (params: Record<string, any>) => http.get('/api/admin/files', params),
}
