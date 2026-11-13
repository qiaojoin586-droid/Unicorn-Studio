// 真实 Vue 组件的调度回归：用相同输入比较工作次数，不据此宣称 GPU/FPS 收益。
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { existsSync } from 'node:fs'
import { mkdir, readdir, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const artifacts = path.resolve(root, '../backend/target/render-scheduling')
const baseline = process.argv.includes('--baseline')
const only = process.argv.find(arg => arg.startsWith('--only='))?.split('=')[1]
const require = createRequire(import.meta.url)
let playwright
for (const candidate of [process.env.PLAYWRIGHT_CORE, 'playwright-core',
  'D:/nodejs/node_global/node_modules/@executeautomation/playwright-mcp-server/node_modules/playwright-core'].filter(Boolean)) {
  try { playwright = require(candidate); break } catch { /* try next */ }
}
assert(playwright, '未找到 playwright-core')
const cache = path.join(os.homedir(), 'AppData/Local/ms-playwright')
const chrome = process.env.CHROME_PATH || (await readdir(cache)).filter(name => name.startsWith('chromium-')).sort().reverse()
  .map(name => path.join(cache, name, 'chrome-win64/chrome.exe')).find(existsSync)
assert(chrome, '未找到 Chromium')

const html = `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body><div id="app"></div><script type="module">
import { createApp, h, ref, watch, onMounted, nextTick } from 'vue';
import { createPinia } from 'pinia'; import { createRouter, createMemoryHistory } from 'vue-router';
import { marked } from 'marked'; import BrandVisual from '/src/components/BrandVisual.vue';
import AiAssistant from '/src/components/AiAssistant.vue';
import { useResizablePanel } from '/src/composables/useResizablePanel.js';
import { useAiChatStore } from '/src/stores/aiChat.js'; import { useUserStore } from '/src/stores/user.js';
import '/src/assets/main.css';
window.__counts = { rects:0, parses:[], drag:0, resize:0 };
const getRect = Element.prototype.getBoundingClientRect;
Element.prototype.getBoundingClientRect = function(...args) {
  if (this.matches('.brand-visual')) window.__counts.rects++;
  return getRect.apply(this, args);
};
const parse = marked.parse;
marked.parse = function(...args) { window.__counts.parses.push({ at:performance.now(), length:args[0].length }); return parse.apply(this,args) };
window.__frames = async (count=2) => { for(let i=0;i<count;i++) await new Promise(requestAnimationFrame); await nextTick(); };
const pinia = createPinia();
const app = createApp({ components:{BrandVisual,AiAssistant}, setup() {
  const visible=ref(true); window.__visible=visible;
  const layout=useResizablePanel(); onMounted(layout.attach); window.__layout=layout;
  window.__resetPanel=()=>{if(layout.width.value!==480||layout.height.value!==600) layout.toggleDefaultSize();layout.resetToDefault()};
  watch(()=>layout.panelStyle.value.left,()=>window.__counts.drag++,{flush:'sync'});
  watch(()=>layout.width.value,()=>window.__counts.resize++,{flush:'sync'});
  return ()=>h('div',[h('div',{style:{width:'420px',height:'420px'}},visible.value?h(BrandVisual):null),visible.value?h(AiAssistant):null]);
} });
app.use(pinia); app.use(createRouter({history:createMemoryHistory(),routes:[{path:'/',component:{template:'<div />'}}]}));
useUserStore(pinia).setSession('scheduling-test', {id:1,nickname:'测试用户'});
window.__ai=useAiChatStore(pinia); app.mount('#app'); window.__ai.isOpen=true;
</script></body></html>`

const server = await createServer({ root, configFile: path.join(root, 'vite.config.js'),
  server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'scheduling-test', configureServer(vite) {
    vite.middlewares.use(async (req, res, next) => {
      const url = new URL(req.url, 'http://localhost')
      if (url.pathname === '/__scheduling') {
        res.setHeader('Content-Type', 'text/html; charset=UTF-8')
        res.end(await vite.transformIndexHtml(url.pathname, html)); return
      }
      if (!url.pathname.startsWith('/api/')) return next()
      res.setHeader('Content-Type', 'application/json')
      res.end(JSON.stringify({ code: 200, data: { available:true, enabled:true, messageAvatarUrl:'', list:[], total:0 } }))
    })
  } }] })
await server.listen()
let browser
try {
  browser = await playwright.chromium.launch({ executablePath: chrome, headless: true })
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 1 })
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/__scheduling`)
  await page.waitForFunction(() => window.__layout && document.querySelector('[data-ai-panel]'))
  await page.evaluate(() => window.__frames(3))
  const report = { baseline, viewport: '1280x900 DPR=1', rounds: 3 }
  if (!only || only === 'brand') {
    report.brand = []
    for (let round=0; round<3; round++) report.brand.push(await page.evaluate(async () => {
      const el=document.querySelector('.brand-visual'); const bounds=el.getBoundingClientRect();
      el.dispatchEvent(new PointerEvent('pointerleave')); await window.__frames(); window.__counts.rects=0;
      for(let i=0;i<96;i++) el.dispatchEvent(new PointerEvent('pointermove', {pointerType:'mouse',clientX:bounds.left+bounds.width*(i+1)/100,clientY:bounds.top+bounds.height*.75}));
      await window.__frames();
      const stage=document.querySelector('.brand-visual__stage');
      const result={events:96,rects:window.__counts.rects,x:parseFloat(stage.style.getPropertyValue('--visual-x')),y:parseFloat(stage.style.getPropertyValue('--visual-y'))};
      el.dispatchEvent(new PointerEvent('pointerleave')); await window.__frames(); return result;
    }))
    if (!baseline) for(const run of report.brand) { assert.equal(run.rects,1); assert(Math.abs(run.x-5.52)<.01); assert.equal(run.y,3) }
    await page.evaluate(async () => {
      const el=document.querySelector('.brand-visual');
      el.dispatchEvent(new PointerEvent('pointermove',{pointerType:'mouse',clientX:200,clientY:200}));
      el.dispatchEvent(new PointerEvent('pointerleave')); await window.__frames();
    })
    assert.equal(await page.locator('.brand-visual__stage').evaluate(el=>el.style.getPropertyValue('--visual-x')), '0px')
  }
  if (!only || only === 'stream') {
    report.stream=[]
    for(let round=0;round<3;round++) report.stream.push(await page.evaluate(async () => {
      const store=window.__ai; store.clearMessages(); store.isLoading=true; await window.__frames(); window.__counts.parses=[];
      for(let i=0;i<60;i++) { store.appendStreamingText('片段'+i+' '); await new Promise(resolve=>setTimeout(resolve,10)); }
      await new Promise(resolve=>setTimeout(resolve,180));
      const parses=[...window.__counts.parses]; const body=document.querySelector('.ai-messages .md-body');
      if(!body?.textContent.includes('片段59')) throw new Error('流式尾部未显示');
      store.finalizeStreaming(); store.isLoading=false; await window.__frames();
      if(!document.querySelector('.ai-messages .md-body')?.textContent.includes('片段59')) throw new Error('结束时丢失尾字');
      return {chunks:60,parses:parses.length,intervals:parses.slice(1).map((p,i)=>Math.round(p.at-parses[i].at))};
    }))
    if(!baseline) for(const run of report.stream) assert(run.intervals.every(ms=>ms>=95), JSON.stringify(run))
    if(!baseline) {
      await page.evaluate(async () => {
        const store=window.__ai; store.clearMessages(); store.isLoading=true; store.setStreamingText('首片'); await window.__frames();
        store.appendStreamingText('最后一字'); await window.__frames(); store.finalizeStreaming(); store.isLoading=false; await window.__frames();
      })
      assert((await page.locator('.ai-messages').innerText()).includes('最后一字'))
      await page.evaluate(async () => {
        const store=window.__ai; store.clearMessages(); store.isLoading=true; store.setStreamingText('关闭前'); await window.__frames();
        store.closeChat(); await window.__frames(); window.__counts.parses=[];
        store.appendStreamingText('关闭后'); await new Promise(resolve=>setTimeout(resolve,160));
        if(window.__counts.parses.length) throw new Error('关闭后仍在解析 Markdown');
        store.openChat(); await window.__frames();
      })
      assert((await page.locator('.ai-messages').innerText()).includes('关闭前关闭后'))
      await page.evaluate(() => {window.__ai.finalizeStreaming();window.__ai.isLoading=false})
      await page.evaluate(async () => {
        const store=window.__ai; store.clearMessages(); store.addAssistantMessage('旧消息段落\n\n'.repeat(150));
        await window.__frames(); const el=document.querySelector('.ai-messages');
        el.scrollTop=0; el.dispatchEvent(new Event('scroll'));
        store.isLoading=true; store.setStreamingText('新回复'); await new Promise(resolve=>setTimeout(resolve,150)); await window.__frames();
        if(el.scrollTop!==0) throw new Error('新分片将上翻阅读的用户拉回底部');
        el.scrollTop=el.scrollHeight; el.dispatchEvent(new Event('scroll'));
        store.appendStreamingText('\n\n新增段落'.repeat(20)); await new Promise(resolve=>setTimeout(resolve,150)); await window.__frames();
        if(el.scrollHeight-el.scrollTop-el.clientHeight>2) throw new Error('已在底部时未跟随渲染后的正文');
        store.finalizeStreaming(); store.isLoading=false;
      })
    }
  }
  if (!only || only === 'panel') {
    report.panel=[]
    for(let round=0;round<3;round++) report.panel.push(await page.evaluate(async () => {
      const layout=window.__layout; layout.resetToDefault(); await window.__frames();
      const start=parseFloat(layout.panelStyle.value.left); window.__counts.drag=0;
      layout.startPairDrag(new PointerEvent('pointerdown',{clientX:500,clientY:300,button:0}));
      for(let i=1;i<=96;i++) window.dispatchEvent(new PointerEvent('pointermove',{clientX:500-i,clientY:300}));
      await window.__frames(); const drag=window.__counts.drag;
      window.dispatchEvent(new PointerEvent('pointerup',{clientX:404,clientY:300})); await window.__frames();
      if(parseFloat(layout.panelStyle.value.left)!==start-96) throw new Error('拖动最终位置不正确');
      layout.resetToDefault(); await window.__frames(); window.__counts.resize=0; const width=layout.width.value;
      layout.startResize('nw',new PointerEvent('pointerdown',{clientX:500,clientY:300,button:0}));
      for(let i=1;i<=48;i++) window.dispatchEvent(new PointerEvent('pointermove',{clientX:500-i,clientY:300-i}));
      await window.__frames(); const resize=window.__counts.resize;
      window.dispatchEvent(new PointerEvent('pointerup',{clientX:452,clientY:252})); await window.__frames();
      if(layout.width.value!==width+48) throw new Error('拉伸最终宽度不正确');
      return {dragEvents:96,positionUpdates:drag,resizeEvents:48,sizeUpdates:resize};
    }))
    if(!baseline) for(const run of report.panel) { assert.equal(run.positionUpdates,1); assert.equal(run.sizeUpdates,1) }
    if(!baseline) {
      // 松手发生在下一帧之前时，也必须写入并持久化最后坐标。
      await page.evaluate(async () => {
        const layout=window.__layout; layout.resetToDefault(); await window.__frames();
        const start=parseFloat(layout.panelStyle.value.left);
        layout.startPairDrag(new PointerEvent('pointerdown',{clientX:500,clientY:300,button:0}));
        window.dispatchEvent(new PointerEvent('pointermove',{clientX:480,clientY:300}));
        window.dispatchEvent(new PointerEvent('pointerup',{clientX:470,clientY:300}));
        const saved=JSON.parse(localStorage.getItem('unicorn.aiChat.panelSize.v3'));
        if(saved.panelX!==start-30) throw new Error('松手坐标未持久化');
        await window.__frames(); if(parseFloat(layout.panelStyle.value.left)!==saved.panelX) throw new Error('松手后仍有过期回调');
      })
      // 八向把手和图标点击使用真实鼠标事件，守护浏览器的捕获、点击与布局行为。
      await page.evaluate(async () => {window.__ai.clearMessages();window.__resetPanel();await window.__frames()})
      for(const dir of ['n','s','w','e','ne','nw','se','sw']) {
        await page.evaluate(async () => {window.__resetPanel();await window.__frames()})
        const before=await page.locator('[data-ai-panel]').boundingBox()
        const iconBefore=await page.locator('[data-ai-icon]').boundingBox()
        const handle=await page.locator('[data-resize="'+dir+'"]').boundingBox()
        const dx=dir.includes('w')?-20:dir.includes('e')?20:0
        const dy=dir.includes('n')?-20:dir.includes('s')?20:0
        const x=handle.x+handle.width/2,y=handle.y+handle.height/2
        await page.mouse.move(x,y);await page.mouse.down()
        await page.mouse.move(x+dx,y+dy,{steps:5});await page.mouse.up();await page.evaluate(()=>window.__frames())
        const after=await page.locator('[data-ai-panel]').boundingBox()
        const expected={x:before.x+(dir.includes('w')?dx:0),y:before.y+(dir.includes('n')?dy:0),
          width:before.width+Math.abs(dx),height:before.height+Math.abs(dy)}
        for(const key of Object.keys(expected)) assert(Math.abs(after[key]-expected[key])<1,dir+' '+key+': '+JSON.stringify({after,expected}))
        const iconAfter=await page.locator('[data-ai-icon]').boundingBox()
        assert.equal(iconAfter.x,iconBefore.x);assert.equal(iconAfter.y,iconBefore.y)
      }
      const icon=page.locator('[data-ai-trigger]')
      await icon.click();await page.locator('[data-ai-panel]').waitFor({state:'hidden'})
      await icon.click();await page.locator('[data-ai-panel]').waitFor()
      await page.waitForFunction(()=>getComputedStyle(document.querySelector('[data-ai-panel]')).transform==='none')
      const saved=await page.evaluate(()=>JSON.parse(localStorage.getItem('unicorn.aiChat.panelSize.v3')))
      await page.reload();await page.waitForFunction(()=>window.__layout&&document.querySelector('[data-ai-panel]'))
      assert.equal(await page.evaluate(()=>window.__layout.width.value),saved.width)
      assert.equal(await page.evaluate(()=>parseFloat(window.__layout.panelStyle.value.left)),saved.panelX)
      await page.evaluate(async () => {
        const layout=window.__layout;window.__resetPanel();await window.__frames();
        layout.startResize('nw',new PointerEvent('pointerdown',{clientX:500,clientY:300,button:0}));
        window.dispatchEvent(new PointerEvent('pointermove',{clientX:480,clientY:280}));
        window.dispatchEvent(new PointerEvent('pointerup',{clientX:470,clientY:270}));
        const saved=JSON.parse(localStorage.getItem('unicorn.aiChat.panelSize.v3'));
        if(saved.width!==510||saved.height!==630) throw new Error('拉伸松手末次尺寸丢失');
        layout.startPairDrag(new PointerEvent('pointerdown',{clientX:500,clientY:300,button:0}));
        window.dispatchEvent(new PointerEvent('pointermove',{clientX:480,clientY:300}));
        window.dispatchEvent(new PointerEvent('pointercancel'));
        const final=layout.panelStyle.value.left;await window.__frames();
        if(layout.dragging.value||layout.panelStyle.value.left!==final) throw new Error('pointercancel 留下在途更新');
        layout.startPairDrag(new PointerEvent('pointerdown',{clientX:500,clientY:300,button:0}));
        window.dispatchEvent(new PointerEvent('pointermove',{clientX:490,clientY:300}));
        window.__ai.closeChat();await window.__frames();
        if(layout.dragging.value||document.body.classList.contains('select-none')) throw new Error('关闭面板未结束拖动');
      })
    }
  }
  if(!baseline) {
    await page.emulateMedia({reducedMotion:'reduce'})
    await page.evaluate(async () => {
      await window.__frames();window.__counts.rects=0;
      document.querySelector('.brand-visual').dispatchEvent(new PointerEvent('pointermove',{pointerType:'mouse',clientX:200,clientY:200}));
      await window.__frames();if(window.__counts.rects) throw new Error('减少动态效果时仍读取交互布局');
    })
    await page.emulateMedia({reducedMotion:'no-preference'})
    await page.evaluate(async () => {
      window.__ai.openChat();window.__ai.clearMessages();window.__ai.setStreamingText('卸载前');await window.__frames();
      window.__ai.appendStreamingText('等待中的末片');
      document.querySelector('.brand-visual').dispatchEvent(new PointerEvent('pointermove',{pointerType:'mouse',clientX:200,clientY:200}));
      window.__layout.startResize('nw',new PointerEvent('pointerdown',{clientX:500,clientY:300,button:0}));
      window.dispatchEvent(new PointerEvent('pointermove',{clientX:490,clientY:290}));
      window.__visible.value=false;await window.__frames();window.__counts.parses=[];
      if(window.__layout.resizing.value||window.__layout.dragging.value) throw new Error('卸载后交互未结束');
      const width=window.__layout.width.value;
      window.dispatchEvent(new PointerEvent('pointermove',{clientX:450,clientY:250}));await window.__frames();
      if(window.__layout.width.value!==width) throw new Error('卸载后仍处理指针移动');
    })
    await page.waitForTimeout(180)
    assert.equal(await page.evaluate(()=>window.__counts.parses.length),0)
  }
  assert.deepEqual(errors,[])
  await mkdir(artifacts,{recursive:true})
  await writeFile(path.join(artifacts,`${baseline?'baseline':'optimized'}${only?'-'+only:''}.json`),JSON.stringify(report,null,2))
  console.log(JSON.stringify(report))
} finally { await browser?.close(); await server.close() }
