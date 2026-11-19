import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const userInfo = ref(JSON.parse(localStorage.getItem('userInfo') || 'null'))

  function setLogin(data) {
    token.value = data.token
    userInfo.value = { userId: data.userId, phone: data.phone, role: data.role }
    localStorage.setItem('token', data.token)
    localStorage.setItem('userInfo', JSON.stringify(userInfo.value))
  }

  function logout() {
    token.value = ''
    userInfo.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('userInfo')
  }

  return { token, userInfo, setLogin, logout }
})

export const useAppStore = defineStore('app', () => {
  const currentVideo = ref('')

  return { currentVideo }
})
