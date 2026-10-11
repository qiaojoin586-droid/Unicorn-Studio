<template>
  <section class="not-found" aria-labelledby="not-found-title">
    <div class="page-shell not-found-layout">
      <div class="not-found-art" aria-hidden="true">
        <span>4</span><span class="not-found-zero"><i></i></span><span>4</span>
        <p>AN UNEXPLORED CORNER.</p>
      </div>
      <div class="not-found-copy">
        <p class="eyebrow">404 / 暂未找到</p>
        <h1 id="not-found-title">这条路径，<br />还没有被探索。</h1>
        <p class="not-found-description">你访问的页面可能已经移动，或这个地址还不存在。换一个方向，精彩的想法仍在继续。</p>
        <div class="not-found-actions">
          <router-link to="/" class="button button--primary">返回首页 <span aria-hidden="true">↗</span></router-link>
          <router-link to="/works" class="not-found-link">探索作品 <span aria-hidden="true">→</span></router-link>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { usePageMeta } from '@/composables/usePageMeta'
usePageMeta({ title: '页面不存在', description: '暂未找到这个页面，返回独角兽工作室首页或继续探索我们的作品。' })
</script>

<style scoped>
.not-found { background: var(--color-ink, #121316); color: var(--color-paper, #f4f2ec); min-height: calc(85svh - var(--header-height, 80px)); display: grid; align-items: center; padding-block: 80px; overflow: hidden; }
.not-found-layout { display: grid; grid-template-columns: 1.1fr 1fr; gap: 96px; align-items: center; width: 100%; }
.not-found-art { position: relative; display: flex; justify-content: center; align-items: center; gap: 4px; font-size: clamp(100px, 15vw, 240px); font-weight: 500; letter-spacing: -.07em; line-height: 1; padding-block: 48px 72px; }
.not-found-zero { display: block; width: .56em; height: .72em; border: .052em solid currentColor; border-radius: 50%; position: relative; transform: rotate(24deg); margin-inline: .015em; }
.not-found-zero::after { content: ''; position: absolute; width: .2em; height: .2em; background: var(--color-ink, #121316); top: -.08em; right: -.08em; }
.not-found-zero i { position: absolute; width: .062em; height: .062em; background: #ef5663; border-radius: 50%; top: -.025em; right: .01em; z-index: 1; }
.not-found-art p { position: absolute; bottom: 12px; left: 0; right: 0; text-align: center; color: var(--color-muted-dark, #a7a8af); font: 10px/1.5 ui-monospace, monospace; letter-spacing: .18em; margin: 0; }
.not-found-copy { min-width: 0; }
.not-found-copy .eyebrow { color: var(--color-accent-on-dark, #ff8c95); margin: 0 0 28px; }
.not-found-copy h1 { font-size: clamp(32px, 3.7vw, 54px); font-weight: 550; letter-spacing: -.045em; line-height: 1.3; margin: 0; }
.not-found-description { max-width: 28em; color: var(--color-muted-dark, #a7a8af); font-size: 15px; line-height: 1.9; margin-block: 24px 32px; }
.not-found-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 20px 32px; }
.not-found-link { display: inline-flex; align-items: center; gap: 24px; min-height: 44px; font-size: 14px; border-bottom: 1px solid #ffffff60; transition: color 160ms, gap 160ms; }
.not-found-link:hover { color: #ff8c95; gap: 30px; }
@media (max-width: 1024px) { .not-found-layout { gap: 48px; } }
@media (max-width: 720px) {
  .not-found { padding-block: 32px 72px; }
  .not-found-layout { grid-template-columns: 1fr; gap: 40px; }
  .not-found-art { font-size: clamp(120px, 29vw, 210px); padding-block: 32px 48px; justify-content: flex-start; }
  .not-found-art p { text-align: left; bottom: 0; }
  .not-found-copy h1 { font-size: clamp(32px, 7.6vw, 44px); }
}
</style>
