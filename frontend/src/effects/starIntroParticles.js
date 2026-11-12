import { tsParticles } from '@tsparticles/engine'
import { loadMeteorsPreset } from '@tsparticles/preset-meteors'
import { loadStarShape } from '@tsparticles/shape-star'

let ready

export function prepareStarIntroEngine() {
  ready ??= Promise.all([loadMeteorsPreset(tsParticles), loadStarShape(tsParticles)])
    .then(() => tsParticles)
    .catch(error => { ready = undefined; throw error })
  return ready
}

export function createStarIntroOptions(width) {
  const count = width < 768 ? 20 : 28
  return {
    preset: 'meteors',
    fullScreen: { enable: false },
    // 预设默认是白色背景，必须显式覆盖，保留主页原有的底色和视频。
    background: { color: '#000000', opacity: 0 },
    detectRetina: false,
    fpsLimit: 45,
    pauseOnBlur: true,
    pauseOnOutsideViewport: false,
    interactivity: {
      detectsOn: 'canvas',
      events: { onClick: { enable: false }, onHover: { enable: false } },
    },
    particles: {
      // 首批星星分布在整个画布，避免只在顶部狭小区域出现。
      number: { value: count, density: { enable: false }, limit: { value: count, mode: 'wait' } },
      paint: { color: { value: ['#FFFFFF', '#FFFFFF', '#FFE2A5', '#FFB8B8'] } },
      shape: { type: 'star', options: { star: { sides: 5, inset: 2 } } },
      size: { value: { min: 2.2, max: 4 } },
      opacity: { value: { min: 0.35, max: 0.7 }, animation: { enable: false } },
      effect: { type: 'trail', options: { trail: { length: 24, fade: true, minWidth: 0, maxWidth: 2.4 } } },
      move: {
        enable: true,
        direction: 'bottom-left',
        angle: { value: 0, offset: 0 },
        speed: { min: 3.5, max: 6.5 },
        straight: true,
        outModes: { default: 'destroy' },
      },
    },
    // 后续从整条顶边和右边补入，继续保持右上向左下的运动方向。
    emitters: [
      {
        position: { x: 50, y: 0 },
        size: { width: 100, height: 0, mode: 'percent' },
        startCount: 0,
        rate: { delay: 0.2, quantity: 1 },
        life: { duration: 3.6, count: 1 },
      },
      {
        position: { x: 100, y: 50 },
        size: { width: 0, height: 100, mode: 'percent' },
        startCount: 0,
        rate: { delay: 0.3, quantity: 1 },
        life: { duration: 3.6, count: 1 },
      },
    ],
  }
}
