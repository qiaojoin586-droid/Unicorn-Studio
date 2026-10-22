<template>
  <div class="editor-container">
    <Toolbar class="editor-toolbar" :editor="editorRef" :defaultConfig="toolbarConfig" mode="default" />
    <Editor
      class="editor-content"
      v-model="html"
      :defaultConfig="editorConfig"
      mode="default"
      @onCreated="handleCreated"
    />
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { Editor, Toolbar } from '@wangeditor/editor-for-vue'
import '@wangeditor/editor/dist/css/style.css'
import { fileApi } from '@/api/file'

const props = defineProps<{ modelValue?: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const editorRef = shallowRef()
const html = ref(props.modelValue || '')

watch(
  () => props.modelValue,
  (v) => {
    if (v !== html.value) html.value = v || ''
  },
)
watch(html, (v) => emit('update:modelValue', v))

const toolbarConfig = {
  excludeKeys: ['group-video'],
}

const editorConfig = {
  autoFocus: false,
  placeholder: '请输入内容…',
  MENU_CONF: {
    uploadImage: {
      async customUpload(file: File, insertFn: (url: string) => void) {
        const result = await fileApi.upload(file)
        insertFn((result as any).url)
      },
    },
  },
}

function handleCreated(editor: any) {
  editorRef.value = editor
}

onBeforeUnmount(() => {
  editorRef.value?.destroy()
})
</script>

<style scoped>
.editor-container {
  border: 1px solid #dcdfe6;
  border-radius: 4px;
}
.editor-content {
  height: 400px;
  overflow-y: hidden;
}
</style>
