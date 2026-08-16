import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const harnessRoot = process.env.DEEPSEEK_HARNESS_SOURCE
if (!harnessRoot) {
  throw new Error('Set DEEPSEEK_HARNESS_SOURCE to a DeepSeek Harness source checkout')
}
const source = resolve(harnessRoot, 'packages/client/ui-primitives/src/BrandWordmark.tsx')
const output = new URL('../app/src/main/res/drawable-xxxhdpi/deepseek_harness_wordmark.png', import.meta.url)
const chrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const work = mkdtempSync(join(tmpdir(), 'dsh-wordmark-'))

try {
  const component = readFileSync(source, 'utf8')
  const start = component.indexOf('<svg')
  const end = component.indexOf('</svg>', start) + '</svg>'.length
  if (start < 0 || end < '</svg>'.length) throw new Error('BrandWordmark SVG not found')

  const svg = component.slice(start, end)
    .replace('width={(size * 182) / 24}', 'width="728"')
    .replace('height={size}', 'height="96"')
    .replace('className={className}', '')
    .replaceAll('clipPath=', 'clip-path=')

  const html = `<!doctype html><html><head><style>
    html,body{margin:0;width:728px;height:96px;overflow:hidden;background:transparent}
    body{color:#f9fafb;--dsw-alias-label-primary-inverted:#353638}
    svg{display:block}
  </style></head><body>${svg}</body></html>`
  const htmlPath = join(work, 'wordmark.html')
  writeFileSync(htmlPath, html)

  execFileSync(chrome, [
    '--headless=new',
    '--disable-gpu',
    '--disable-background-networking',
    '--disable-component-update',
    '--hide-scrollbars',
    '--no-first-run',
    '--default-background-color=00000000',
    '--force-device-scale-factor=1',
    '--window-size=728,96',
    `--user-data-dir=${join(work, 'chrome')}`,
    `--screenshot=${output.pathname}`,
    `file://${htmlPath}`,
  ], { stdio: 'inherit' })
} finally {
  rmSync(work, { recursive: true, force: true })
}
