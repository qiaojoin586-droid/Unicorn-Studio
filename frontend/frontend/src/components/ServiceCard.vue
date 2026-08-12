<template>
  <div class="bg-white rounded-xl shadow-md p-8 text-center hover:shadow-lg transition">
    <div class="text-5xl mb-4">{{ icon }}</div>
    <h3 class="text-xl font-semibold mb-3">{{ title }}</h3>
    <p class="text-gray-600 leading-relaxed">{{ description }}</p>
  </div>
</template>

<script setup>
defineProps({
  title: {
    type: String,
    required: true,
  },
  description: {
    type: String,
    required: true,
  },
  icon: {
    type: String,
    default: '✨',
  },
})
</script>
