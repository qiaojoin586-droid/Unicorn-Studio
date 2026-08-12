<template>
  <div class="min-h-[70vh] bg-gray-950 px-6 flex items-center justify-center text-center">
    <div>
      <p class="text-indigo-400 font-semibold mb-3">404</p>
      <h1 class="text-3xl sm:text-4xl font-bold text-white mb-4">页面不存在</h1>
      <p class="text-gray-400 mb-8">访问地址可能已失效，或页面已经移动。</p>
      <router-link
        to="/"
        class="inline-flex items-center bg-indigo-600 hover:bg-indigo-500 text-white font-semibold px-6 py-3 rounded-lg transition"
      >
        返回首页
      </router-link>
    </div>
  </div>
</template>