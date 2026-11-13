// Presentation API types; empty forms contain no business content.
export interface PresentationBackground {
  backgroundMode: '' | 'image' | 'video'
  backgroundImageUrl: string
  backgroundVideoUrl: string
}
export interface HomePresentation extends PresentationBackground {
  eyebrow: string
  title: string
  description: string
  primaryLabel: string
  primaryUrl: string
  secondaryLabel: string
  secondaryUrl: string
  featuredLimit: number | null
  worksEyebrow: string
  worksTitle: string
  worksDescription: string
  capabilitiesEyebrow: string
  capabilitiesTitle: string
  capabilitiesDescription: string
  directionsEyebrow: string
  directionsTitle: string
  directionsDescription: string
  invitationEyebrow: string
  invitationTitle: string
  invitationDescription: string
  invitationLabel: string
  invitationUrl: string
  recommendationType: '' | 'work' | 'learning' | 'join'
  recommendationId: string
}
export interface AboutPresentation extends PresentationBackground {
  principlesEyebrow: string
  principlesTitle: string
  principles: { title: string; description: string }[]
  invitationTitle: string
  invitationDescription: string
  invitationLabel: string
  invitationUrl: string
}
export interface FooterPresentation {
  inheritBrand: boolean
  inheritContact: boolean
  brandTagline: string
  noteEyebrow: string
  noteTitle: string
  noteLinkLabel: string
  noteLinkUrl: string
  wordmark: string
}
export interface JoinPresentation {
  platform: string
  groupNumber: string
  joinUrl: string
  phoneHelp: string
  invitationText: string
}
export interface PresentationSections {
  home: HomePresentation
  about: AboutPresentation
  footer: FooterPresentation
  join: JoinPresentation
}
export type PresentationSection = keyof PresentationSections
export const presentationFields: { [K in PresentationSection]: (keyof PresentationSections[K])[] }
export function createPresentationSection<K extends PresentationSection>(section: K): PresentationSections[K]
export function createPresentation(): PresentationSections
export function mergePresentation(value?: Partial<{ [K in PresentationSection]: Partial<PresentationSections[K]> }>): PresentationSections
