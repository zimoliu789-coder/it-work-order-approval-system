<script setup lang="ts">
import type { MenuItem } from '@/config/menus'

/**
 * 递归菜单树（ 菜单结构）
 *
 * 支持三级结构：系统管理 → 员工管理 → 员工档案
 *
 *  收尾优化·：**移除了未交付菜单的 `P{phase}` 阶段徽标**。
 * 到  全部功能已交付，徽标不仅过时，还会让使用者误以为系统仍是半成品 ——
 * 阶段编号是交付过程信息，不该长期出现在面向用户的界面上。
 */
defineProps<{
  items: MenuItem[]
}>()
</script>

<template>
  <template v-for="item in items" :key="item.path">
    <el-sub-menu v-if="item.children && item.children.length > 0" :index="item.path">
      <template #title>
        <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
        <span>{{ item.title }}</span>
      </template>
      <MenuTree :items="item.children" />
    </el-sub-menu>

    <el-menu-item v-else :index="item.path">
      <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
      <template #title>
        <span>{{ item.title }}</span>
      </template>
    </el-menu-item>
  </template>
</template>
