import { useAuthStore } from '@/stores/auth'

/** 权限判断 hook。 */
export function usePermission() {
  const auth = useAuthStore()

  return {
    has: (code: string) => auth.hasPerm(code),
    hasAny: (codes: string[]) => auth.hasAnyPerm(codes),
  }
}
