import { http } from '@/utils/request'

/** 快速链接项 */
export interface FooterLink {
  label: string
  url: string
  newTab: boolean
}

/**
 * 社交图标项。
 * icon 为内置图标库 key（见 @/constants/socialIcons）；iconUrl 为自定义图标地址，非空时优先生效。
 */
export interface FooterSocial {
  icon: string
  iconUrl: string
  url: string
  title: string
}

/** 页脚配置（与后端 FooterVO 一一对应） */
export interface FooterConfig {
  brandName: string
  brandIntro: string
  showParticles: boolean
  linksTitle: string
  links: FooterLink[]
  socialTitle: string
  socials: FooterSocial[]
  email: string
  emailLabel: string
  copyrightLeft: string
  copyrightRight: string
}

export const footerApi = {
  get: () => http.get<FooterConfig>('/api/admin/footer'),
  update: (data: FooterConfig) => http.put<void>('/api/admin/footer', data),
}
