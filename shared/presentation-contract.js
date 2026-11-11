// Field contract only. Page content is loaded from the presentation API.
export const presentationFields = {
  "home": [
    "eyebrow",
    "title",
    "description",
    "primaryLabel",
    "primaryUrl",
    "secondaryLabel",
    "secondaryUrl",
    "featuredLimit",
    "worksEyebrow",
    "worksTitle",
    "worksDescription",
    "capabilitiesEyebrow",
    "capabilitiesTitle",
    "capabilitiesDescription",
    "directionsEyebrow",
    "directionsTitle",
    "directionsDescription",
    "invitationEyebrow",
    "invitationTitle",
    "invitationDescription",
    "invitationLabel",
    "invitationUrl",
    "recommendationType",
    "recommendationId",
    "backgroundMode",
    "backgroundImageUrl",
    "backgroundVideoUrl"
  ],
  "about": [
    "principlesEyebrow",
    "principlesTitle",
    "principles",
    "invitationTitle",
    "invitationDescription",
    "invitationLabel",
    "invitationUrl",
    "backgroundMode",
    "backgroundImageUrl",
    "backgroundVideoUrl"
  ],
  "footer": [
    "inheritBrand",
    "inheritContact",
    "brandTagline",
    "noteEyebrow",
    "noteTitle",
    "noteLinkLabel",
    "noteLinkUrl",
    "wordmark"
  ],
  "join": [
    "platform",
    "groupNumber",
    "joinUrl",
    "phoneHelp",
    "invitationText"
  ]
}

export function createPresentationSection(section) {
  if (!Object.hasOwn(presentationFields, section)) throw new Error('Unknown presentation section')
  return Object.fromEntries(presentationFields[section].map(key => [key,
    key === 'principles' ? [] : key === 'featuredLimit' ? null : key.startsWith('inherit') ? false : ''
  ]))
}

export function createPresentation() {
  return Object.fromEntries(Object.keys(presentationFields).map(section => [section, createPresentationSection(section)]))
}

export function mergePresentation(value = {}) {
  const result = createPresentation()
  for (const [section, keys] of Object.entries(presentationFields)) {
    const incoming = value?.[section]
    if (!incoming || typeof incoming !== 'object' || Array.isArray(incoming)) continue
    for (const key of keys) {
      if (Object.hasOwn(incoming, key) && incoming[key] != null) result[section][key] = JSON.parse(JSON.stringify(incoming[key]))
    }
  }
  return result
}
