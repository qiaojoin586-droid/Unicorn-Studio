<template>
  <div>
    <AiAvatar />
    <AiChatDialog />
    <!-- 登录/注册弹窗必须挂在这一层，不能放 AiChatDialog 内：那边根节点带
         v-if="store.isOpen"，对话面板关闭时整棵子树不渲染，顶部导航的「登录」就点不动了。
         本组件只依赖 Pinia store（useAiChatStore / useAiChat），与父子关系无关，挪上来无影响。 -->
    <AiLoginPrompt />
    <AiConfirmDialog />
  </div>
</template>

<script setup>
import AiAvatar from './AiAvatar.vue'
import AiChatDialog from './AiChatDialog.vue'
import AiLoginPrompt from './AiLoginPrompt.vue'
import AiConfirmDialog from './AiConfirmDialog.vue'
</script>
