// 真实 tsParticles + Vue 浏览器回归；API 使用本地数据，不访问线上服务。
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { existsSync } from 'node:fs'
import { mkdir, readdir } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const artifacts = path.resolve(root, '../backend/target/star-intro')
await mkdir(artifacts, { recursive: true })
const require = createRequire(import.meta.url)
let playwright
for (const candidate of [process.env.PLAYWRIGHT_CORE, 'playwright-core',
  'D:/nodejs/node_global/node_modules/@executeautomation/playwright-mcp-server/node_modules/playwright-core'].filter(Boolean)) {
  try { playwright = require(candidate); break } catch { /* next */ }
}
assert(playwright, '未找到 playwright-core')
const cache = path.join(os.homedir(), 'AppData/Local/ms-playwright')
const chrome = process.env.CHROME_PATH || (await readdir(cache)).filter(name => name.startsWith('chromium-')).sort().reverse()
  .map(name => path.join(cache, name, 'chrome-win64/chrome.exe')).find(existsSync)
assert(chrome, '未找到 Chromium')
const html = `<!doctype html><html lang="zh-CN"><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body><div id="app"></div><script type="module">
import { createApp,h,ref,nextTick } from 'vue'; import { createPinia } from 'pinia';
import { createRouter,createMemoryHistory } from 'vue-router'; import App from '/src/App.vue';
import Home from '/src/pages/HomePage.vue'; import StarIntro from '/src/components/StarIntroEffect.vue'; import '/src/assets/main.css';
const isolated=new URLSearchParams(location.search).has('isolated');const active=ref(false);
window.__finished=0;window.__loads=0;window.__destroyed=0;window.__mount=()=>active.value=true;window.__unmount=()=>active.value=false;
window.__frames=async()=>{await nextTick();await new Promise(requestAnimationFrame);await nextTick()};
window.__engine=async()=>{const m=await import('@tsparticles/engine');return m.tsParticles};
window.__trapLoad=async(mode)=>{
 const engine=await window.__engine();const load=engine.load.bind(engine);
 engine.load=async(...args)=>{window.__loads++;if(mode==='fail')throw Error('test load failure');
  const result=await load(...args);if(result){const destroy=result.destroy.bind(result);result.destroy=(...args)=>{if(!result.destroyed)window.__destroyed++;return destroy(...args)}}
  window.__created=result;if(mode==='late')await new Promise(resolve=>window.__resolveLoad=resolve);return result;
 };
};
window.__trackListeners=()=>{
 const listeners=new Map();
 for(const target of [window,document,MediaQueryList.prototype]){
  const add=target.addEventListener,remove=target.removeEventListener;
  target.addEventListener=function(type,fn,options){let byTarget=listeners.get(this);if(!byTarget)listeners.set(this,byTarget=new Map());
   const key=type+':'+Boolean(typeof options==='boolean'?options:options?.capture);let set=byTarget.get(key);if(!set)byTarget.set(key,set=new Set());set.add(fn);return add.call(this,type,fn,options)};
  target.removeEventListener=function(type,fn,options){const key=type+':'+Boolean(typeof options==='boolean'?options:options?.capture);listeners.get(this)?.get(key)?.delete(fn);return remove.call(this,type,fn,options)};
 }
 window.__listenerCount=()=>[...listeners.values()].flatMap(map=>[...map.values()]).reduce((count,set)=>count+set.size,0);
};
const app=createApp(isolated?{setup:()=>()=>h('div',[active.value?h(StarIntro,{onFinished:()=>{active.value=false;window.__finished++}}):null])}:App);
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:Home},{path:'/other',component:{render:()=>h('div','其他页面')}}]});
app.use(createPinia());app.use(router);await router.push('/');await router.isReady();app.mount('#app');window.__router=router;window.__ready=true;
</script></body></html>`
const server = await createServer({ root, configFile: path.join(root, 'vite.config.js'),
  optimizeDeps: { include: ['@tsparticles/engine', '@tsparticles/preset-meteors', '@tsparticles/shape-star'] },
  server: { host:'127.0.0.1',port:0,open:false }, plugins:[{name:'star-intro-test',configureServer(vite){
    vite.middlewares.use(async(req,res,next)=>{
      const url=new URL(req.url,'http://localhost')
      if(url.pathname==='/__stars'){res.setHeader('Content-Type','text/html; charset=UTF-8');res.end(await vite.transformIndexHtml(url.pathname,html));return}
      if(!url.pathname.startsWith('/api/'))return next()
      const data=url.pathname==='/api/site/presentation'?{home:{eyebrow:'UNICORN STUDIO',title:'让好奇心\n长出作品',description:'在这里学习、探索与创造，让想象成为看得见的作品。',primaryLabel:'探索作品',primaryUrl:'/works',secondaryLabel:'了解工作室',secondaryUrl:'/about'}}:{list:[],total:0}
      res.setHeader('Content-Type','application/json');res.end(JSON.stringify({code:200,data}))
    })
  }}] })
await server.listen()
const base=`http://127.0.0.1:${server.httpServer.address().port}/__stars`
let browser
const errors=[]
async function pageFor(options={}) {
  const page=await browser.newPage({viewport:{width:1440,height:900},reducedMotion:'no-preference',...options})
  page.on('pageerror',error=>errors.push(error.message))
  if(process.env.DEBUG_STARS) {
    page.on('console',message=>console.log('browser:',message.type(),message.text().slice(0,300)))
    page.on('framenavigated',frame=>{if(!frame.parentFrame())console.log('navigate:',frame.url())})
  }
  await page.addInitScript(()=>localStorage.setItem('mouse-trail','off'))
  await page.route('**/*.mp4',route=>route.abort())
  return page
}
async function ready(page,url=base){await page.goto(url);await page.waitForFunction(()=>window.__ready)}
async function live(page){
  await page.evaluate(async()=>{window.__tsEngine=await window.__engine()})
  await page.waitForFunction(()=>{window.__liveContainer=window.__tsEngine.items.find(item=>!item.destroyed&&item.particles.count>0);return Boolean(window.__liveContainer)})
}
async function empty(page){
  await page.evaluate(async()=>{window.__tsEngine=await window.__engine()})
  await page.waitForFunction(()=>!window.__tsEngine.items.length)
  assert.equal(await page.locator('.star-intro').count(),0)
}
try {
  browser=await playwright.chromium.launch({executablePath:chrome,headless:true})
  const home=await pageFor();await ready(home);await live(home)
  await home.locator('.hero__primary').waitFor()
  if(process.env.DEBUG_STARS) console.log(await home.evaluate(()=>({ready:window.__ready,live:Boolean(window.__liveContainer),key:sessionStorage.getItem('has_seen_star_effect'),host:document.querySelector('.star-intro')?.outerHTML,now:performance.now()})))
  const first=await home.evaluate(async()=>{
    const container=window.__liveContainer;window.__container=container;
    const destroy=container.destroy.bind(container);container.destroy=(...args)=>{window.__destroyed++;return destroy(...args)};
    const canvas=document.querySelector('.star-intro canvas'),style=getComputedStyle(canvas),host=getComputedStyle(canvas.parentElement);
    const rect=canvas.getBoundingClientRect(),points=[];
    for(let index=0;index<container.particles.count;index++)points.push(container.particles.get(index).position);
    const title=document.querySelector('.hero__title-text');
    const copy=document.querySelector('.hero__primary');const bounds=copy.getBoundingClientRect();
    return {count:container.particles.count,shape:container.actualOptions.particles.shape.type,
      limit:container.actualOptions.particles.number.limit.value,direction:container.actualOptions.particles.move.direction,
      transparent:style.backgroundColor,pointer:style.pointerEvents,fixed:host.position,
      click:document.elementFromPoint(bounds.x+bounds.width/2,bounds.y+bounds.height/2)?.closest('a')===copy,
      key:sessionStorage.getItem('has_seen_star_effect'),
      fullViewport:Math.abs(rect.width-document.documentElement.clientWidth)<=1&&Math.abs(rect.height-innerHeight)<=1&&rect.x===0&&rect.y===0,
      canvasRect:{x:rect.x,y:rect.y,width:rect.width,height:rect.height},viewport:{width:document.documentElement.clientWidth,height:innerHeight},
      portal:canvas.parentElement.parentElement===document.body,mask:host.maskImage,
      coversBothAxes:Math.min(...points.map(p=>p.x))<innerWidth/2&&Math.max(...points.map(p=>p.x))>innerWidth/2
        &&Math.min(...points.map(p=>p.y))<innerHeight/2&&Math.max(...points.map(p=>p.y))>innerHeight/2,
      titleColor:getComputedStyle(title).color,titleText:title.textContent};
  })
  assert(first.count>0&&first.count<=28,JSON.stringify(first));assert.equal(first.shape,'star');assert.equal(first.direction,'bottom-left')
  assert.equal(first.limit,28);assert.equal(first.transparent,'rgba(0, 0, 0, 0)');assert.equal(first.pointer,'none');assert.equal(first.fixed,'fixed');assert(first.click);assert.equal(first.key,'1')
  assert(first.fullViewport,JSON.stringify(first));assert(first.portal);assert.equal(first.mask,'none');assert(first.coversBothAxes)
  assert.equal(first.titleText,'让好奇心\n长出作品')
  await home.waitForTimeout(1000)
  const movement=await home.evaluate(()=>{
    const particle=window.__container.particles.get(0);return {x:particle.velocity.x,y:particle.velocity.y,trail:particle.trail?.length}
  })
  assert(movement.x<0&&movement.y>0&&movement.trail>0,JSON.stringify(movement))
  assert.notEqual(await home.locator('.hero__title-text').evaluate(node=>getComputedStyle(node).color),first.titleColor)
  await home.screenshot({path:path.join(artifacts,'desktop.png')})
  await home.locator('.star-intro.is-fading').waitFor({timeout:6000});await home.locator('.star-intro').waitFor({state:'detached'});await empty(home)
  assert.equal(await home.evaluate(()=>window.__destroyed),1)
  const afterReload=[];home.on('request',request=>{if(request.url().includes('starIntroParticles'))afterReload.push(request.url())})
  await home.reload();await home.waitForFunction(()=>window.__ready);await home.waitForTimeout(250)
  assert.equal(await home.locator('.star-intro').count(),0);assert.equal(afterReload.length,0)
  await home.evaluate(async()=>{await window.__router.push('/other');await window.__router.push('/')})
  assert.equal(await home.locator('.star-intro').count(),0)
  await home.evaluate(()=>window.scrollTo(0,1200))
  await home.waitForFunction(()=>getComputedStyle(document.querySelector('.hero__title-text')).animationPlayState==='paused')
  await home.evaluate(()=>window.scrollTo(0,0))
  await home.waitForFunction(()=>getComputedStyle(document.querySelector('.hero__title-text')).animationPlayState==='running')
  await home.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,get:()=>true});document.dispatchEvent(new Event('visibilitychange'))})
  assert.equal(await home.locator('.hero__title-text').evaluate(node=>getComputedStyle(node).animationPlayState),'paused')
  await home.close()
  console.log('首页：全屏覆盖、左右上下粒子分布、星形拖尾、点击穿透、4秒后销毁、刷新跳过、标题变色与离屏/后台暂停通过')

  const phone=await pageFor({viewport:{width:390,height:844},deviceScaleFactor:2});await ready(phone);await live(phone)
  assert.equal(await phone.evaluate(async()=>(await window.__engine()).items[0].actualOptions.particles.number.limit.value),20)
  assert(await phone.evaluate(()=>{const rect=document.querySelector('.star-intro canvas').getBoundingClientRect();return Math.abs(rect.width-document.documentElement.clientWidth)<=1&&Math.abs(rect.height-innerHeight)<=1}))
  assert(await phone.evaluate(()=>{const canvas=document.querySelector('.star-intro canvas');return Math.abs(canvas.width-canvas.clientWidth)<=1}))
  await phone.waitForTimeout(650);await phone.screenshot({path:path.join(artifacts,'phone.png')})
  await phone.evaluate(()=>window.__router.push('/other'));await empty(phone);await phone.close()

  const reduced=await pageFor({reducedMotion:'reduce'});const skipped=[]
  reduced.on('request',request=>{if(request.url().includes('starIntroParticles'))skipped.push(request.url())})
  await ready(reduced);await reduced.waitForTimeout(200);assert.equal(await reduced.locator('.star-intro').count(),0);assert.equal(skipped.length,0)
  assert.equal(await reduced.locator('.hero__title-text').evaluate(node=>getComputedStyle(node).animationName),'none')
  await reduced.evaluate(()=>document.documentElement.dataset.theme='light')
  assert.equal(await reduced.locator('.home-page').evaluate(node=>getComputedStyle(node).getPropertyValue('--hero-title-rose').trim()),'#b42339')
  await reduced.close()

  // 存储异常使用内存兜底；路由切换和减少动态效果都必须释放实例与事件监听。
  const blocked=await pageFor();await blocked.addInitScript(()=>Object.defineProperty(window,'sessionStorage',{get(){throw new DOMException('blocked','SecurityError')}}))
  await ready(blocked,base+'?isolated=1');await blocked.evaluate(async()=>{await window.__trapLoad();window.__trackListeners();window.__mount()});await live(blocked)
  await blocked.emulateMedia({reducedMotion:'reduce'});await empty(blocked)
  assert.equal(await blocked.evaluate(()=>window.__listenerCount()),0)
  await blocked.emulateMedia({reducedMotion:'no-preference'});await blocked.evaluate(()=>window.__mount());await blocked.waitForTimeout(150)
  assert.equal(await blocked.locator('.star-intro').count(),0);assert.equal(await blocked.evaluate(()=>window.__loads),1);await blocked.close()

  const late=await pageFor();await ready(late,base+'?isolated=1')
  await late.evaluate(async()=>{await window.__trapLoad('late');window.__trackListeners();window.__mount()})
  await late.waitForFunction(()=>window.__created);await late.evaluate(()=>window.__unmount());await late.evaluate(()=>window.__resolveLoad());await empty(late)
  assert.equal(await late.evaluate(()=>window.__destroyed),1);assert.equal(await late.evaluate(()=>window.__listenerCount()),0)
  assert.equal(await late.evaluate(()=>sessionStorage.getItem('has_seen_star_effect')),null);await late.close()

  const fail=await pageFor();await ready(fail,base+'?isolated=1');await fail.evaluate(async()=>{await window.__trapLoad('fail');window.__trackListeners();window.__mount()})
  await fail.waitForFunction(()=>window.__finished===1);await empty(fail);assert.equal(await fail.evaluate(()=>window.__listenerCount()),0);await fail.close()

  const fallback=await pageFor();await ready(fallback,base+'?isolated=1')
  await fallback.evaluate(async()=>{await window.__trapLoad();window.__trackListeners();window.__mount()});await live(fallback)
  await fallback.evaluate(()=>document.querySelector('.star-intro').style.transition='none')
  await fallback.waitForFunction(()=>window.__finished===1,{},{timeout:6000});await empty(fallback)
  assert.equal(await fallback.evaluate(()=>window.__destroyed),1);assert.equal(await fallback.evaluate(()=>window.__listenerCount()),0);await fallback.close()

  const hidden=await pageFor();await hidden.addInitScript(()=>{
    window.__fakeHidden=true;Object.defineProperty(document,'hidden',{configurable:true,get:()=>window.__fakeHidden})
  })
  await ready(hidden,base+'?isolated=1');await hidden.evaluate(()=>{window.__trackListeners();window.__mount()});await hidden.waitForTimeout(100)
  assert.equal(await hidden.locator('.star-intro').count(),0)
  await hidden.evaluate(()=>{window.__fakeHidden=false;document.dispatchEvent(new Event('visibilitychange'))});await live(hidden)
  await hidden.evaluate(()=>{window.__fakeHidden=true;document.dispatchEvent(new Event('visibilitychange'))});await empty(hidden)
  assert.equal(await hidden.evaluate(()=>window.__listenerCount()),0);await hidden.close()

  const pending=await pageFor();await ready(pending,base+'?isolated=1')
  await pending.route('**/src/effects/starIntroParticles.js*',async route=>{await new Promise(resolve=>setTimeout(resolve,300));await route.continue()})
  const requested=pending.waitForRequest(request=>request.url().includes('/src/effects/starIntroParticles.js'))
  await pending.evaluate(()=>window.__mount());await requested;await pending.evaluate(()=>window.__unmount());await pending.waitForTimeout(600);await empty(pending)
  assert.equal(await pending.evaluate(()=>sessionStorage.getItem('has_seen_star_effect')),null)
  await pending.evaluate(()=>window.__mount());await live(pending);await pending.evaluate(()=>window.__unmount());await empty(pending);await pending.close()
  assert.deepEqual(errors,[])
  console.log('手机DPR限制、减少动效、存储禁用、异步取消、加载失败、淡出兜底、后台标签页及事件监听清理通过')
} finally {await browser?.close();await server.close()}
