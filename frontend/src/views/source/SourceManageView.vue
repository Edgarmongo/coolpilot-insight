<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>数据源能力</h2>
        <p class="sub">
          同一行能力有三个视角，刻意不合并：<b>配置</b>是你声明的期望，<b>运行时</b>是采集服务实际加载了什么，
          <b>结论</b>是两者对照后的准入判定。<br />
          配置说支持，不等于运行时可用；反过来，运行时加载了，也不等于业务允许执行。
        </p>
      </div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-alert
      v-if="listSnapshotState && listSnapshotState !== 'FRESH'"
      class="banner"
      type="warning"
      :closable="false"
      show-icon
      :title="`运行时快照状态为 ${listSnapshotState}：此时任何能力都被判为 UNKNOWN，请不要据此认定「能力不可用」`"
      description="快照不可用只说明采集服务的注册表没能取到，不能说明平台不支持该能力。"
    />

    <el-table :data="rows" v-loading="loading" class="tbl">
      <el-table-column label="平台" min-width="160">
        <template #default="{ row }">
          <div class="name">{{ row.name }}</div>
          <div class="code">{{ row.code }}</div>
        </template>
      </el-table-column>
      <el-table-column label="启用状态" width="110">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">
            {{ row.status === 'ENABLED' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="已声明能力" width="120">
        <template #default="{ row }">{{ enabledCount(row) }}</template>
      </el-table-column>
      <el-table-column label="可执行能力" width="120">
        <template #default="{ row }">
          <span :class="executableCount(row) ? 'ok' : 'muted'">{{ executableCount(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="运行时快照" width="120">
        <template #default="{ row }">
          <el-tag :type="stateType(row.snapshotState)" size="small">{{ row.snapshotState }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="配置可读性" min-width="200">
        <template #default="{ row }">
          <span v-if="row.desiredError" class="bad" :title="row.desiredError">
            {{ shortError(row.desiredError) }}
          </span>
          <span v-else class="muted">正常</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openDetail(row)">三态明细</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="drawer" :title="`${current.name || ''} · 能力三态`" size="76%">
      <div v-loading="detailLoading">
        <el-alert
          v-if="detail && detail.snapshotState !== 'FRESH'"
          type="warning"
          :closable="false"
          show-icon
          class="banner"
          :title="`运行时快照 ${detail.snapshotState}：下面「运行时」与「结论」两列不得视为事实`"
          :description="detail.snapshotDetail || ''"
        />

        <el-alert
          v-if="detail && detail.desiredError"
          type="error"
          :closable="false"
          show-icon
          class="banner"
          title="配置（Desired）无法解析，该数据源下所有能力都不会被允许执行"
          :description="detail.desiredError"
        />

        <div class="meta">
          <span>结构版本：<b>{{ detail?.desiredSchemaVersion || '—' }}</b></span>
          <span>快照：<b>{{ detail?.snapshotId || '无' }}</b></span>
          <span>生成于：<b>{{ detail?.snapshotGeneratedAt || '—' }}</b></span>
        </div>

        <h4 class="sec">对照表</h4>
        <el-table :data="detailRows" size="small" class="tbl">
          <el-table-column prop="code" label="能力码" width="150" />
          <el-table-column prop="category" label="分类" width="100" />
          <el-table-column label="配置（Desired）" width="150">
            <template #default="{ row }">
              <el-tag v-if="row.desiredStatus" size="small"
                      :type="row.desiredStatus === 'ENABLED' ? 'success' : 'info'">
                {{ row.desiredStatus === 'ENABLED' ? '已声明' : '已禁用' }}
              </el-tag>
              <span v-else class="muted">未声明</span>
            </template>
          </el-table-column>
          <el-table-column label="运行时（Observed）" min-width="180">
            <template #default="{ row }">
              <template v-if="row.implementationStatus">
                <div>{{ row.implementationStatus }}</div>
                <div class="sub2">
                  证据：{{ evidenceText(row) }}
                </div>
              </template>
              <span v-else class="muted">未注册</span>
            </template>
          </el-table-column>
          <el-table-column label="结论（Effective）" min-width="220">
            <template #default="{ row }">
              <el-tag size="small" :type="effType(row.effectiveStatus)">{{ row.effectiveStatus }}</el-tag>
              <span class="reason">{{ row.effectiveReason }}</span>
              <div class="sub2">{{ row.detail }}</div>
            </template>
          </el-table-column>
          <el-table-column label="可执行" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.executable ? 'success' : 'info'">
                {{ row.executable ? '允许' : '不允许' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>

        <h4 class="sec">
          配置（Desired）
          <el-button v-if="hasPerm('source:capability:manage')" size="small" type="primary" plain
                     class="add-btn" @click="addRow">添加能力</el-button>
        </h4>
        <el-alert
          v-if="!hasPerm('source:capability:manage')"
          type="info"
          :closable="false"
          show-icon
          title="当前账号只能查看，不能修改配置（缺少 source:capability:manage）"
          class="banner"
        />

        <el-table :data="draft" size="small" class="tbl">
          <el-table-column label="接入（connectorCode）" min-width="180">
            <template #default="{ row }">
              <el-select v-model="row.connectorCode" size="small"
                         :disabled="!editable" placeholder="选择接入">
                <el-option v-for="c in connectorOptions" :key="c" :label="c" :value="c" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="能力码" width="170">
            <template #default="{ row }">
              <el-select v-model="row.code" size="small" :disabled="!editable" @change="row.category = CATEGORY[row.code]">
                <el-option v-for="c in CAPABILITIES" :key="c" :label="c" :value="c" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="分类" width="110">
            <template #default="{ row }">{{ row.category || '—' }}</template>
          </el-table-column>
          <el-table-column label="允许的访问模式" min-width="190">
            <template #default="{ row }">
              <el-checkbox-group v-model="row.accessModes" size="small" :disabled="!editable">
                <el-checkbox value="PUBLIC">公开</el-checkbox>
                <el-checkbox value="AUTHENTICATED">登录</el-checkbox>
              </el-checkbox-group>
            </template>
          </el-table-column>
          <el-table-column label="声明" width="130">
            <template #default="{ row }">
              <el-switch v-model="row.desiredStatus" active-value="ENABLED" inactive-value="DISABLED"
                         :disabled="!editable" active-text="启用" inactive-text="禁用" inline-prompt />
            </template>
          </el-table-column>
          <el-table-column label="" width="70">
            <template #default="{ $index }">
              <el-button v-if="editable" size="small" link type="danger" @click="draft.splice($index, 1)">
                移除
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="editable" class="actions">
          <el-button @click="resetDraft">还原</el-button>
          <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { sourceApi } from '../../api'
import { usePermission } from '../../composables/usePermission'

const { hasPerm } = usePermission()

// 能力码 → 分类。与 SOURCE-02-capability-schema.json 的 $defs.capability 一一对应；
// 这里只是把契约里的固定搭配做成下拉联动，不产生任何"猜测"。
const CATEGORY = {
  KEYWORD_SEARCH: 'SEARCH',
  CONTENT_DETAIL: 'CONTENT',
  COMMENT: 'COMMENT',
  COMMENT_REPLY: 'COMMENT',
  AUTHOR_PROFILE: 'USER',
  AUTHOR_POSTS: 'USER',
  MEDIA_DOWNLOAD: 'MEDIA',
}
const CAPABILITIES = Object.keys(CATEGORY)

const rows = ref([])
const loading = ref(false)
const drawer = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const current = ref({})
const draft = ref([])
const saving = ref(false)

const editable = computed(() => hasPerm('source:capability:manage'))
const listSnapshotState = computed(() => {
  const states = new Set((rows.value || []).map((r) => r.snapshotState))
  if (!states.size) return null
  if (states.has('UNAVAILABLE')) return 'UNAVAILABLE'
  if (states.has('STALE')) return 'STALE'
  return 'FRESH'
})

const connectorOptions = computed(() => {
  const set = new Set()
  for (const c of detail.value?.observed || []) set.add(c.connectorCode)
  for (const c of detail.value?.desired || []) set.add(c.connectorCode)
  return [...set]
})

/**
 * 对照表：以 Effective 为骨架（它已覆盖 Desired ∪ Observed 的候选），
 * 再把 Desired / Observed 的事实贴上去。
 * Effective 为空（例如两边都取不到）时退化为 Desired 行，
 * 并明确显示"无结论"而不是留空表。
 */
const detailRows = computed(() => {
  const d = detail.value
  if (!d) return []
  const observedByCode = new Map()
  for (const conn of d.observed || []) {
    for (const cap of conn.capabilities || []) observedByCode.set(cap.code, cap)
  }
  const desiredByCode = new Map()
  for (const cap of d.desired || []) {
    const prev = desiredByCode.get(cap.code)
    // 同一能力多接入时，只要有一条 ENABLED 就算"已声明"（与后端旧字段派生口径一致）
    if (!prev || (prev.desiredStatus !== 'ENABLED' && cap.desiredStatus === 'ENABLED')) {
      desiredByCode.set(cap.code, cap)
    }
  }

  const eff = d.effective || []
  const base = eff.length
    ? eff
    : [...desiredByCode.keys()].map((code) => ({
        code,
        category: desiredByCode.get(code)?.category,
        effectiveStatus: '—',
        effectiveReason: '无核对结论',
        executable: false,
        detail: '当前没有任何可用于核对的候选能力',
      }))

  return base.map((e) => ({
    code: e.code,
    category: e.category,
    effectiveStatus: e.effectiveStatus,
    effectiveReason: e.effectiveReason,
    executable: e.executable,
    detail: e.detail,
    desiredStatus: desiredByCode.get(e.code)?.desiredStatus || null,
    implementationStatus: observedByCode.get(e.code)?.implementationStatus || null,
    evidence: observedByCode.get(e.code)?.evidence || null,
  }))
})

const evidenceText = (row) => {
  if (!row.evidence) return '无'
  if (row.evidence.accepted === true) return '已接受'
  if (row.evidence.accepted === false) return `已拒绝（${row.evidence.rejectionReason || '原因未给出'}）`
  return '未提交'
}

const enabledCount = (row) => (row.desired || []).filter((c) => c.desiredStatus === 'ENABLED').length
const executableCount = (row) => (row.effective || []).filter((c) => c.executable).length
const shortError = (msg) => (msg.length > 42 ? `${msg.slice(0, 42)}…` : msg)

const stateType = (s) => (s === 'FRESH' ? 'success' : s === 'STALE' ? 'warning' : 'danger')
const effType = (s) => {
  if (s === 'AVAILABLE') return 'success'
  if (s === 'DEGRADED') return 'warning'
  if (s === 'SOURCE_DISABLED' || s === 'UNKNOWN') return 'info'
  return 'danger'
}

async function load() {
  loading.value = true
  try {
    const res = await sourceApi.list()
    rows.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  current.value = row
  drawer.value = true
  await reloadDetail()
}

async function reloadDetail() {
  detailLoading.value = true
  try {
    const res = await sourceApi.capabilities(current.value.code)
    detail.value = res.data
    resetDraft()
  } finally {
    detailLoading.value = false
  }
}

function resetDraft() {
  draft.value = (detail.value?.desired || []).map((c) => ({
    connectorCode: c.connectorCode,
    code: c.code,
    category: c.category,
    accessModes: [...(c.accessModes || [])],
    desiredStatus: c.desiredStatus,
    adapterVersion: c.adapterVersion || undefined,
  }))
}

function addRow() {
  if (!connectorOptions.value.length) {
    ElMessage.warning('还不知道该平台的接入标识（connectorCode）：请先确认采集服务已加载对应接入，再添加能力')
    return
  }
  const used = new Set(draft.value.map((r) => `${r.connectorCode}/${r.code}`))
  const free = CAPABILITIES.find((c) => !used.has(`${connectorOptions.value[0]}/${c}`))
  if (!free) {
    ElMessage.info('所有能力都已声明')
    return
  }
  draft.value.push({
    connectorCode: connectorOptions.value[0],
    code: free,
    category: CATEGORY[free],
    accessModes: ['PUBLIC'],
    desiredStatus: 'ENABLED',
  })
}

async function save() {
  // 前端只做"必填提醒"，真正的判据在后端：接口会逐字段判类型与枚举，
  // 前端放过去的东西后端一样会拒。
  for (const [i, r] of draft.value.entries()) {
    if (!r.connectorCode || !r.code) {
      ElMessage.warning(`第 ${i + 1} 行缺少接入或能力码`)
      return
    }
    if (!r.accessModes?.length) {
      ElMessage.warning(`第 ${i + 1} 行至少要选一种访问模式`)
      return
    }
  }
  saving.value = true
  try {
    const doc = {
      schemaVersion: '2.0',
      capabilities: draft.value.map((r) => ({
        sourceCode: current.value.code,
        connectorCode: r.connectorCode,
        code: r.code,
        category: r.category || CATEGORY[r.code],
        accessModes: r.accessModes,
        desiredStatus: r.desiredStatus,
        ...(r.adapterVersion ? { adapterVersion: r.adapterVersion } : {}),
      })),
    }
    await sourceApi.saveCapabilities(current.value.code, doc)
    ElMessage.success('配置已保存')
    await Promise.all([reloadDetail(), load()])
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page { padding: 4px; }
.page-head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; gap: 16px; }
.page-head h2 { margin: 0 0 4px; font-size: 18px; }
.sub { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; max-width: 820px; line-height: 1.7; }
.tbl { width: 100%; }
.banner { margin-bottom: 12px; }
.name { font-weight: 600; }
.code { color: var(--el-text-color-placeholder); font-size: 12px; }
.muted { color: var(--el-text-color-placeholder); }
.ok { color: var(--el-color-success); font-weight: 600; }
.bad { color: var(--el-color-danger); }
.meta { display: flex; gap: 24px; flex-wrap: wrap; color: var(--el-text-color-secondary); font-size: 12px; margin-bottom: 8px; }
.sec { margin: 20px 0 8px; font-size: 14px; display: flex; align-items: center; gap: 8px; }
.reason { margin-left: 8px; font-size: 12px; color: var(--el-text-color-secondary); }
.sub2 { font-size: 12px; color: var(--el-text-color-placeholder); line-height: 1.5; margin-top: 2px; }
.actions { margin-top: 12px; display: flex; justify-content: flex-end; gap: 8px; }
.add-btn { margin-left: auto; }
</style>
