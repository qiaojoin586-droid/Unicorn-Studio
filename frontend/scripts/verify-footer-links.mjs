import assert from 'node:assert/strict'
import { safeFooterLink, safeLink } from '../src/utils/links.js'

const cases = [
  ['works', '/works'],
  ['./works', '/works'],
  ['../works', '/works'],
  ['works/one?view=grid#more', '/works/one?view=grid#more'],
  ['?page=2', '/?page=2'],
  ['#contact', '#contact'],
  ['/works', '/works'],
  ['https://example.com/works', 'https://example.com/works'],
  ['mailto:team@example.com', 'mailto:team@example.com'],
  ['tel:+8612345678', 'tel:+8612345678'],
  ['  works  ', '/works'],
  ['', ''],
  [null, ''],
  ['javascript:alert(1)', ''],
  ['data:text/html,hello', ''],
  ['sms:+123', ''],
  ['//evil.example/path', ''],
  ['works/..//evil.example', ''],
  ['works\\evil', ''],
  ['works\n/evil', ''],
  ['\tworks', ''],
  ['https://[invalid', ''],
]
for (const [input, expected] of cases) assert.equal(safeFooterLink(input), expected, JSON.stringify(input))
assert.equal(safeLink('works'), '', 'new presentation links must still require rooted paths')
assert.equal(safeLink('mailto:team@example.com'), '', 'presentation links do not accept contact schemes')
console.log(`Footer link regression passed: ${cases.length + 2} assertions`)
