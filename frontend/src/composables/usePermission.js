import { computed, ref } from 'vue'
import request from '../api/request'

/**
 * 按钮级权限。
 *
 * 只影响「按钮显不显示」，**不构成安全边界**——真正的拦截在后端
 * @RequirePermission。用户改前端状态最多让按钮出现，点下去仍是 403。
 *
 * 结果缓存在模块级，多个组件共用一次请求；切换账号时调用 refresh() 重取。
 */
const codes = ref(new Set())
const isAdmin = ref(false)
let loaded = false

async function load() {
  try {
    const res = await request.get('/v1/system/permissions/mine')
    isAdmin.value = !!res.data?.admin
    codes.value = new Set(res.data?.codes || [])
    loaded = true
  } catch (e) {
    // 拉取失败时按"无权限"处理：宁可不显示按钮，也不要让用户点了才失败
    codes.value = new Set()
  }
}

export function usePermission() {
  if (!loaded) load()

  /** 管理员默认拥有全部权限 */
  function hasPerm(code) {
    if (isAdmin.value) return true
    return codes.value.has(code)
  }

  function hasAny(...list) {
    return list.some((c) => hasPerm(c))
  }

  function refresh() {
    loaded = false
    return load()
  }

  return { hasPerm, hasAny, isAdmin: computed(() => isAdmin.value), refresh }
}
