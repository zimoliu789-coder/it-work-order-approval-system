import axios, { AxiosError, type AxiosInstance, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import type { ApiResponse } from '@/types/api'
import { MOCK_ENABLED, mockRequest } from '@/mock'

/**
 * Axios 封装（ 会话机制、 统一响应、 UI 提示、 限流提示）
 *
 * 关键设计：
 * 1. withCredentials=true —— JWT 存放在 HttpOnly Cookie 中，必须允许携带凭证；
 * 2. 全局注入 X-Requested-With —— CSRF 第二层防护，服务端要求所有写请求携带该头；
 * 3. 统一解包 ApiResponse，业务层直接拿到 data；
 * 4. 401 自动跳登录、FORCE_CHANGE_PASSWORD 自动跳强制改密页、429 按 Retry-After 给出准确等待时间。
 */

/** 业务错误：携带后端错误码，便于调用方按 code 分支处理 */
export class ApiError extends Error {
  readonly code: string
  readonly traceId: string
  readonly httpStatus: number
  /** 限流（429）时的建议等待秒数；0 表示服务端未给出 */
  readonly retryAfterSeconds: number

  constructor(code: string, message: string, traceId = '', httpStatus = 0, retryAfterSeconds = 0) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
    this.httpStatus = httpStatus
    this.retryAfterSeconds = retryAfterSeconds
  }
}

const CSRF_HEADER_NAME = 'X-Requested-With'
const CSRF_HEADER_VALUE = 'XMLHttpRequest'

const instance: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 20000,
  withCredentials: true,
  headers: {
    // 刻意**不设**默认 Content-Type：
    //   1) 普通对象负载，axios 会自动写入 application/json；
    //   2) 一旦在此写死 application/json，axios 的 transformRequest 会把 FormData（文件上传）
    //      也 serialize 成 JSON 字符串，后端 multipart 解析失败（表现为 500 INTERNAL_ERROR）；
    //   3) 留空后，XHR 适配器会为 FormData 让浏览器写入带 boundary 的 multipart/form-data。
    [CSRF_HEADER_NAME]: CSRF_HEADER_VALUE
  }
})

/** 避免 401 连续触发多次跳转 */
let redirecting = false

function redirectTo(path: string): void {
  if (redirecting) {
    return
  }
  const current = window.location.pathname + window.location.search
  if (current.startsWith(path)) {
    return
  }
  redirecting = true
  const redirect = path === '/login' ? `?redirect=${encodeURIComponent(current)}` : ''
  window.location.href = `${path}${redirect}`
}

instance.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  // 防御性补写 CSRF 头（例如通过配置覆盖 headers 时）
  config.headers.set(CSRF_HEADER_NAME, CSRF_HEADER_VALUE)
  return config
})

instance.interceptors.response.use(
  // 拦截器在此处把统一信封解包成业务数据，与 axios 声明的 AxiosResponse 返回类型不一致，
  // 故用显式 any 过渡；对调用方的真实类型由 request<T> 的第二个泛型保证。
  (response): any => {
    const body = response.data as ApiResponse<unknown> | undefined
    if (!body || typeof body.code !== 'string') {
      // 非统一信封：不要静默透传，否则调用方拿到整个响应体、取字段得到 undefined，
      // 且 TS 完全无法察觉（这类静默失败最难排查）
      throw new ApiError('INVALID_ENVELOPE', '服务端响应格式异常，请联系管理员', '', response.status)
    }
    if (body.code === 'SUCCESS') {
      return body.data
    }
    // 兜底处理 HTTP 200 + 业务码非 SUCCESS 的情况：
    // 必须与 onRejected 共用同一套处理，否则 401/403/429 的跳转与提示会被绕过
    throw buildApiError(
      body.code,
      body.message,
      body.traceId ?? '',
      response.status,
      response.config?.url ?? '',
      readRetryAfter(response.headers as unknown as Record<string, unknown>, body)
    )
  },
  (error: AxiosError<ApiResponse<unknown>>) => {
    const body = error.response?.data
    const status = error.response?.status ?? 0
    const code = body?.code ?? 'NETWORK_ERROR'
    const message = body?.message ?? resolveNetworkMessage(error)
    const traceId = body?.traceId ?? ''
    return Promise.reject(
      buildApiError(
        code,
        message,
        traceId,
        status,
        error.config?.url ?? '',
        readRetryAfter(error.response?.headers as unknown as Record<string, unknown> | undefined, body)
      )
    )
  }
)

/**
 * 解析限流等待秒数。
 *
 * 两个来源，缺一不可：
 * 1. `Retry-After` 响应头 —— HTTP 标准语义，应用层与 Nginx 层限流都会带上；
 * 2. 响应体 `data.retryAfterSeconds` —— 兜底。中间代理/网关可能过滤自定义头，
 *    但响应体一定会到达业务代码，两者互为保险。
 */
function readRetryAfter(
  headers: Record<string, unknown> | undefined,
  body: ApiResponse<unknown> | undefined
): number {
  const headerRaw = headers?.['retry-after'] ?? headers?.['Retry-After']
  const fromHeader = Number(headerRaw)
  if (Number.isFinite(fromHeader) && fromHeader > 0) {
    return Math.ceil(fromHeader)
  }
  const data = body?.data
  if (data && typeof data === 'object') {
    const fromBody = Number((data as Record<string, unknown>).retryAfterSeconds)
    if (Number.isFinite(fromBody) && fromBody > 0) {
      return Math.ceil(fromBody)
    }
  }
  return 0
}

/**
 * 统一的业务错误处理：401 / 403 / 428 / 429 的提示与跳转只在此处维护一份。
 * onFulfilled 与 onRejected 都必须经过这里，避免「200 + 非 SUCCESS」绕过跳转逻辑。
 */
function buildApiError(
  code: string,
  message: string,
  traceId: string,
  status: number,
  url: string,
  retryAfterSeconds = 0
): ApiError {
  //  ：后端「强制绑定联系方式」闸门（与强制改密同级）。
  //
  // 刻意放在 /auth/me 静默分支**之前**：登录响应下发的 requireContactBinding 会过期
  // （典型场景：管理员后来清空了他的手机号与邮箱），此时代码 403 才是唯一权威判据。
  // 若被静默吞掉，用户会看到「页面一直在转圈」却不知道要去绑定 —— 因为拦他的是后端，
  // 而前端还认为他一切正常。
  if (code === 'CONTACT_BIND_REQUIRED') {
    ElMessage.warning('请先绑定手机号或邮箱')
    redirectTo('/bind-contact')
    return new ApiError(code, message, traceId, status, retryAfterSeconds)
  }
  // 会话探测接口（/auth/me）静默失败：由路由守卫负责跳转登录，
  // 否则每次首次访问都会弹出「登录已失效」提示
  if (url.endsWith('/auth/me')) {
    return new ApiError(code, message, traceId, status, retryAfterSeconds)
  }
  if (code === 'FORCE_CHANGE_PASSWORD' || status === 428) {
    ElMessage.warning('请先修改初始密码')
    redirectTo('/change-password')
  } else if (status === 401) {
    ElMessage.error(message || '登录状态已失效，请重新登录')
    redirectTo('/login')
  } else if (status === 403) {
    ElMessage.error(message || '无权限执行该操作')
  } else if (status === 429 || code === 'RATE_LIMITED') {
    // 限流不是「报错」而是「稍后再来」，因此用 warning 而非 error：
    // 给出明确等待秒数，用户才知道该等多久，而不是反复点击继续撞限流。
    // 注意 Nginx 层限流返回的响应体可能不是统一信封（骨架里没有 code 字段），
    // 此时 backend message 是「请求失败（HTTP 429）」这类无意义文案，
    // 因此优先用 header/data 里的秒数自行拼装提示。
    if (retryAfterSeconds > 0) {
      ElMessage.warning(`操作过于频繁，请 ${retryAfterSeconds} 秒后重试`)
    } else {
      ElMessage.warning(message || '操作过于频繁，请稍后重试')
    }
  } else {
    ElMessage.error(message)
  }
  return new ApiError(code, message, traceId, status, retryAfterSeconds)
}

function resolveNetworkMessage(error: AxiosError): string {
  if (error.code === 'ECONNABORTED') {
    return '请求超时，请检查网络或后端服务是否已启动'
  }
  if (!error.response) {
    return '无法连接后端服务，请确认后端已在 8080 端口启动'
  }
  return `请求失败（HTTP ${error.response.status}）`
}

/**
 * 统一请求入口：Mock 模式下直接返回本地假数据，方便后端未就绪时走查 UI
 */
async function request<T>(config: AxiosRequestConfig): Promise<T> {
  if (MOCK_ENABLED) {
    const response = await mockRequest<T>(config.method ?? 'GET', config.url ?? '', config.data)
    if (response.code !== 'SUCCESS') {
      throw new ApiError(response.code, response.message, response.traceId, 200)
    }
    return response.data
  }
  // 响应拦截器已把 ApiResponse 解包为业务数据，而 axios 的类型声明仍描述为 AxiosResponse，
  // 该落差无法用泛型参数表达（axios 1.x 的 request<T, R> 不改变其返回类型），
  // 故用一次显式的 unknown 中转断言；真实类型由调用方传入的 T 保证。
  return (await instance.request(config)) as unknown as T
}

export const http = {
  get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
    return request<T>({ method: 'GET', url, params })
  },
  post<T>(url: string, data?: unknown): Promise<T> {
    return request<T>({ method: 'POST', url, data })
  },
  put<T>(url: string, data?: unknown): Promise<T> {
    return request<T>({ method: 'PUT', url, data })
  },
  /**
   * 表单上传（multipart）。
   *
   * 单列一个方法而不是复用 post：调用方若不小心在别的层设了 Content-Type，
   * 就会重现「FormData 被序列化成 JSON → 后端 500」这个已在附件上传踩过的坑。
   * 入口收窄到一处，排查时只需看这一个方法。
   */
  postForm<T>(url: string, form: FormData, onProgress?: (percent: number) => void): Promise<T> {
    return request<T>({
      method: 'POST',
      url,
      data: form,
      onUploadProgress: (event) => {
        if (onProgress && event.total) {
          onProgress(Math.min(100, Math.round((event.loaded / event.total) * 100)))
        }
      }
    })
  },
  /**
   * 裸字节流上传（`application/octet-stream`）。
   *
   * 专供在线升级包使用，与 `postForm` 并列而不是复用它，原因是体量与超时：
   * 1. **体量**：升级包可达数百 MB，而 multipart 的上限是**全局配置**
   *    （`spring.servlet.multipart.max-file-size`，当前 20MB）。为了一个偶发的大文件
   *    把全局值抬到几百 MB，等于同时放宽了附件上传的容器层防护 —— 而那一层正是
   *    「业务层限额之外再挡一道」的设计所在。改用裸流后，限额由后端在**边读边计数**中执行。
   * 2. **超时**：实例级 timeout 是 20 秒，是为普通 JSON 请求设的。
   *    把它用在几百 MB 的上传上，会在网络稍慢时稳定地「传到一半超时」，
   *    而错误提示是「请求超时，请检查网络」—— 看起来像网络问题，实际是配置问题。
   *    因此这里为它单独放开一个远大于实例默认值的超时。
   *
   * 这里显式设置 `Content-Type` 是安全的（且必要）：负载是 Blob，
   * 不存在 `postForm` 注释里那种「FormData 被序列化成 JSON、boundary 丢失」的风险。
   */
  postBinary<T>(
    url: string,
    body: Blob,
    onProgress?: (percent: number) => void,
    timeoutMs = 600000
  ): Promise<T> {
    return request<T>({
      method: 'POST',
      url,
      data: body,
      timeout: timeoutMs,
      headers: { 'Content-Type': 'application/octet-stream' },
      onUploadProgress: (event) => {
        if (onProgress && event.total) {
          onProgress(Math.min(100, Math.round((event.loaded / event.total) * 100)))
        }
      }
    })
  },
  delete<T>(url: string, params?: Record<string, unknown>): Promise<T> {
    return request<T>({ method: 'DELETE', url, params })
  }
}

export default instance
