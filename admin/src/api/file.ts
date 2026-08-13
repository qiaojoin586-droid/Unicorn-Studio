import { http } from '@/utils/request'
import service from '@/utils/request'

export const fileApi = {
  /** 上传（multipart），返回 {url, name, size} */
  upload: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return service.post('/api/admin/files/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
  list: (params: Record<string, any>) => http.get('/api/admin/files', params),
}
