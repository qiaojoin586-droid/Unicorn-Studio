/** @type {import('tailwindcss').Config} */
export default {
  content: [
    './index.html',
    './src/**/*.{vue,js,ts,jsx,tsx}',
  ],
  theme: {
    extend: {},
  },
  // W-20：全站模板从未使用 `container` 类，但构建 CSS 里出现了 `.container{width:100%}` 及 6 条
  // @media 变体 —— Tailwind 的默认提取器会把 JS 标识符当候选类名（如 useMarkdown.js 的
  // `hardenLinks(container)` 参数名）。关掉该 core plugin 消除这段死 CSS。
  corePlugins: {
    container: false,
  },
  plugins: [],
}
