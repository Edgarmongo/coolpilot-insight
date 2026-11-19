<template>
  <div class="stats-cards">
    <div class="stat-card">
      <div class="stat-value">{{ summary.totalComments || 0 }}</div>
      <div class="stat-label">评论总数</div>
    </div>
    <div class="stat-card">
      <div class="stat-value">{{ summary.totalUsers || 0 }}</div>
      <div class="stat-label">参与用户数</div>
    </div>
    <div class="stat-card">
      <div class="stat-value">{{ formatNum(summary.totalLikes || 0) }}</div>
      <div class="stat-label">总点赞数</div>
    </div>
    <div class="stat-card">
      <div class="stat-value">{{ summary.avgLikes || 0 }}</div>
      <div class="stat-label">平均点赞</div>
    </div>
  </div>
</template>

<script setup>
defineProps({ summary: { type: Object, default: () => ({}) } })
function formatNum(n) {
  if (n >= 10000) return (n / 10000).toFixed(1) + 'w'
  return String(n)
}
</script>
