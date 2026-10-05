import { describe, expect, it } from 'vitest'
import { AD_DEFAULTS, type AdConfigVO } from '@/types/ad'
import {
  DEFAULT_AD_USER_FILTER,
  buildAdPayload,
  emptyAdForm,
  fillAdForm,
  firstMissingField,
  lastSyncSucceeded,
  type AdFormState
} from './adForm'

/**
 * AD 域控配置表单纯逻辑单测（；需求文档 五·）
 *
 * 本次唯一的价值主张是「把 11 个技术字段压成 4 个必填项」，因此覆盖重点全部落在
 * **压缩过程中最容易静默出错的三处**：
 *
 * 1. `baseDn` 的「留空 = 请系统推导」语义 —— 若空串被当值传下去，服务端就不再推导，
 *    而界面上的推导预览行仍然显示推导结果，**显示的值与落库的值当场分家**；
 * 2. 「自动推导出来的基础 DN 不算手工指定」—— 这条规则决定维护人员换了域地址之后，
 *    基础 DN 会不会跟着变。它只会在「换域控」那天才暴露，所以必须由单测钉住；
 * 3. `firstMissingField` 只校验 3 项（不含两个 DN）—— 多校验一项就等于把
 *    「简化成 4 项」的需求又还回去了。
 *
 * 与 `orderForm.spec.ts` / `deviceForm.spec.ts` 同一约定：组件只负责把事件接到这些规则上。
 */

/** 服务端配置夹具：只覆盖用例关心的字段，其余用 的默认值补齐 */
function vo(overrides: Partial<AdConfigVO> = {}): AdConfigVO {
  return {
    enabled: false,
    serverUrls: 'dc01.company.com',
    serverPort: 389,
    useSsl: false,
    strictCert: true,
    baseDn: null,
    bindDn: null,
    bindPassword: '',
    bindPasswordConfigured: false,
    userFilter: DEFAULT_AD_USER_FILTER,
    attrLogin: 'sAMAccountName',
    attrName: 'displayName',
    attrEmail: 'mail',
    attrPhone: 'telephoneNumber',
    attrDept: 'department',
    attrStatus: 'userAccountControl',
    defaultRole: 'user',
    connectTimeoutSeconds: 5,
    syncEnabled: false,
    syncHour: 2,
    ...overrides
  }
}

function form(overrides: Partial<AdFormState> = {}): AdFormState {
  return { ...emptyAdForm(), ...overrides }
}

describe('emptyAdForm 默认值', () => {
  it('「系统自动处理」的项取 AD_DEFAULTS，不散落魔法值', () => {
    const f = emptyAdForm()
    expect(f.serverPort).toBe(AD_DEFAULTS.port)
    expect(f.connectTimeoutSeconds).toBe(AD_DEFAULTS.timeoutSeconds)
    expect(f.syncHour).toBe(AD_DEFAULTS.syncHour)
    expect(f.attrLogin).toBe(AD_DEFAULTS.attrLogin)
    expect(f.attrName).toBe(AD_DEFAULTS.attrName)
    expect(f.attrEmail).toBe(AD_DEFAULTS.attrEmail)
    expect(f.attrPhone).toBe(AD_DEFAULTS.attrPhone)
    expect(f.attrDept).toBe(AD_DEFAULTS.attrDept)
    expect(f.attrStatus).toBe(AD_DEFAULTS.attrStatus)
  })

  it('四个主表单字段全部为空、开关默认关 —— 未配置时不该「看起来已经配好了」', () => {
    const f = emptyAdForm()
    expect(f.enabled).toBe(false)
    expect(f.serverUrls).toBe('')
    expect(f.bindDn).toBe('')
    expect(f.bindPassword).toBe('')
    // 两个 DN 默认留空 = 交给系统推导（而不是留一个可能过期的旧值）
    expect(f.baseDn).toBe('')
    expect(f.syncEnabled).toBe(false)
  })

  it('用户过滤器默认值与后端 AdConnection.DEFAULT_FILTER 一致', () => {
    expect(emptyAdForm().userFilter).toBe(DEFAULT_AD_USER_FILTER)
    expect(DEFAULT_AD_USER_FILTER).toBe('(&(objectClass=user)(sAMAccountName={0}))')
  })

  it('每次调用返回全新对象（组件重置表单时不会共享引用）', () => {
    const a = emptyAdForm()
    a.serverUrls = 'changed'
    expect(emptyAdForm().serverUrls).toBe('')
  })
})

describe('fillAdForm 回填', () => {
  it('库里的基础 DN 恰好等于当前域地址的推导结果时留空 —— 换域地址后会自动跟着变', () => {
    const f = fillAdForm(vo({ baseDn: 'DC=company,DC=com' }), 'DC=company,DC=com')
    expect(f.baseDn).toBe('')
  })

  it('人工在高级选项填过的值（与推导结果不同）原样保留，绝不被改写', () => {
    const f = fillAdForm(vo({ baseDn: 'OU=Staff,DC=corp,DC=local' }), 'DC=company,DC=com')
    expect(f.baseDn).toBe('OU=Staff,DC=corp,DC=local')
  })

  it('推不出基础 DN（IP 域地址 ⇒ null）时，库里已有的值必须保留 —— 否则会把它悄悄抹掉', () => {
    const f = fillAdForm(vo({ baseDn: 'DC=company,DC=com' }), null)
    expect(f.baseDn).toBe('DC=company,DC=com')
  })

  it('两侧都为空时留空（不产生 `null` 字符串）', () => {
    expect(fillAdForm(vo({ baseDn: null }), null).baseDn).toBe('')
    expect(fillAdForm(vo({ baseDn: '' }), null).baseDn).toBe('')
  })

  it('比较前先 trim，避免「只差一个空格」被误判成手工指定', () => {
    expect(fillAdForm(vo({ baseDn: ' DC=company,DC=com ' }), 'DC=company,DC=com').baseDn).toBe('')
  })

  it('绑定密码恒为空 —— 服务端只回掩码，留空即表示「保持原值」', () => {
    // 即便服务端出于某种原因回了内容，也不能把它当作用户填的新密码
    expect(fillAdForm(vo({ bindPassword: '****' }), null).bindPassword).toBe('')
  })

  it('同步开关与时刻随配置一起回填（ 从系统参数搬过来的两列）', () => {
    const f = fillAdForm(vo({ syncEnabled: true, syncHour: 6 }), null)
    expect(f.syncEnabled).toBe(true)
    expect(f.syncHour).toBe(6)
  })

  it('同步时刻为 null 时回落 2（凌晨两点），不产生 NaN / undefined', () => {
    expect(fillAdForm(vo({ syncHour: null as unknown as number }), null).syncHour).toBe(
      AD_DEFAULTS.syncHour
    )
  })

  it('手机号属性为 null 时回填空串（高级选项里显示为空 = 不同步手机号）', () => {
    expect(fillAdForm(vo({ attrPhone: null }), null).attrPhone).toBe('')
  })

  it('strictCert 只有显式 false 才视为关闭（历史数据缺字段时按严格模式处理）', () => {
    expect(fillAdForm(vo({ strictCert: false }), null).strictCert).toBe(false)
    expect(fillAdForm(vo({ strictCert: true }), null).strictCert).toBe(true)
    expect(
      fillAdForm(vo({ strictCert: undefined as unknown as boolean }), null).strictCert
    ).toBe(true)
  })

  it('布尔字段用 `=== true` 判定 —— 后端非布尔真值时不会误开启', () => {
    const f = fillAdForm(
      vo({ enabled: 'false' as unknown as boolean, syncEnabled: 0 as unknown as boolean }),
      null
    )
    expect(f.enabled).toBe(false)
    expect(f.syncEnabled).toBe(false)
  })

  it('不修改入参（便于单测与「取消编辑」）', () => {
    const source = vo({ baseDn: 'DC=x' })
    fillAdForm(source, 'DC=x')
    expect(source.baseDn).toBe('DC=x')
  })
})

describe('buildAdPayload 组装载荷', () => {
  it('空白一律落 null —— 这是「留空 = 交给系统推导」的物理载体', () => {
    const p = buildAdPayload(form({ baseDn: '', bindDn: '', attrPhone: '' }))
    expect(p.baseDn).toBeNull()
    expect(p.bindDn).toBeNull()
    expect(p.attrPhone).toBeNull()
  })

  it('纯空白（空格 / 制表符）同样落 null，不能因为「非空串」就当成手工值', () => {
    const p = buildAdPayload(form({ baseDn: '   ', bindDn: '\t', attrPhone: '  ' }))
    expect(p.baseDn).toBeNull()
    expect(p.bindDn).toBeNull()
    expect(p.attrPhone).toBeNull()
  })

  it('填了的 DN 去掉首尾空白后原样传下去（不做任何大小写 / 形状改写）', () => {
    const p = buildAdPayload(
      form({ baseDn: ' OU=Staff,DC=a,DC=b ', bindDn: ' company\\query ' })
    )
    expect(p.baseDn).toBe('OU=Staff,DC=a,DC=b')
    expect(p.bindDn).toBe('company\\query')
  })

  it('文本字段统一 trim，避免把「看起来一样的地址」存成两条不同配置', () => {
    const p = buildAdPayload(
      form({
        serverUrls: ' dc01.company.com ',
        userFilter: ' (&(objectClass=user)) ',
        attrLogin: ' sAMAccountName ',
        attrName: ' displayName ',
        attrEmail: ' mail ',
        attrDept: ' department ',
        attrStatus: ' userAccountControl '
      })
    )
    expect(p.serverUrls).toBe('dc01.company.com')
    expect(p.userFilter).toBe('(&(objectClass=user))')
    expect(p.attrLogin).toBe('sAMAccountName')
    expect(p.attrName).toBe('displayName')
    expect(p.attrEmail).toBe('mail')
    expect(p.attrDept).toBe('department')
    expect(p.attrStatus).toBe('userAccountControl')
  })

  it('绑定密码不做 trim —— 密码里的首尾空格是密码的一部分', () => {
    expect(buildAdPayload(form({ bindPassword: ' pw ' })).bindPassword).toBe(' pw ')
  })

  it('端口 / 超时 / 同步时刻原样透传，且同步开关是布尔而非 truthy 值', () => {
    const p = buildAdPayload(
      form({ serverPort: 636, connectTimeoutSeconds: 15, syncEnabled: true, syncHour: 23 })
    )
    expect(p.serverPort).toBe(636)
    expect(p.connectTimeoutSeconds).toBe(15)
    expect(p.syncEnabled).toBe(true)
    expect(p.syncHour).toBe(23)
  })
})

describe('firstMissingField 必填校验', () => {
  it('三项齐备时返回 null', () => {
    const f = form({ serverUrls: 'dc01.company.com', bindDn: 'company\\query', bindPassword: 'p' })
    expect(firstMissingField(f, false)).toBeNull()
  })

  it('缺域服务器地址 → 给带示例的提示', () => {
    const msg = firstMissingField(form({ serverUrls: '', bindDn: 'company\\query' }), false)
    expect(msg).toContain('域服务器地址')
    expect(msg).toContain('dc01.company.com')
  })

  it('缺域管理员账号 → 提示里同时给出下行式与 UPN 两种写法', () => {
    const msg = firstMissingField(form({ serverUrls: 'dc01.company.com', bindDn: '' }), false)
    expect(msg).toContain('域管理员账号')
    expect(msg).toContain('company\\query')
    expect(msg).toContain('query@company.com')
  })

  it('缺密码且库里从未配过 → 提示填密码', () => {
    const f = form({ serverUrls: 'dc01.company.com', bindDn: 'company\\query', bindPassword: '' })
    expect(firstMissingField(f, false)).toContain('域管理员密码')
  })

  it('表单留空但库里已配过密码 → 视为不缺失（这是「编辑时不重填密码」的正常路径）', () => {
    const f = form({ serverUrls: 'dc01.company.com', bindDn: 'company\\query', bindPassword: '' })
    expect(firstMissingField(f, true)).toBeNull()
  })

  it('刻意不校验两个 DN —— 它们由系统推导，要求填写等于把「简化成 4 项」还回去', () => {
    const f = form({ serverUrls: 'dc01.company.com', bindDn: 'company\\query', bindPassword: 'p' })
    // baseDn 与 bindDn 之外的 DN 项此时都不参与判定，故整体通过
    expect(f.baseDn).toBe('')
    expect(firstMissingField(f, false)).toBeNull()
  })

  it('按「地址 → 账号 → 密码」的顺序返回首个问题，一次只提示一条', () => {
    const f = form({ serverUrls: '', bindDn: '', bindPassword: '' })
    expect(firstMissingField(f, false)).toContain('域服务器地址')

    const f2 = form({ serverUrls: 'dc01.company.com', bindDn: '', bindPassword: '' })
    expect(firstMissingField(f2, false)).toContain('域管理员账号')
  })

  it('纯空白的地址与账号等同于未填', () => {
    const f = form({ serverUrls: '   ', bindDn: '  ', bindPassword: 'p' })
    expect(firstMissingField(f, false)).toContain('域服务器地址')
  })

  it('未启用也能通过校验 —— 校验只关心「能不能连上」，启不启用由调用方决定', () => {
    const f = form({
      enabled: false,
      serverUrls: 'dc01.company.com',
      bindDn: 'company\\query',
      bindPassword: 'p'
    })
    expect(firstMissingField(f, false)).toBeNull()
  })
})

describe('lastSyncSucceeded 留痕文本解析', () => {
  it('无记录 / 空文本 → null（界面显示「尚未同步」，而不是「失败」）', () => {
    expect(lastSyncSucceeded(null)).toBeNull()
    expect(lastSyncSucceeded(undefined)).toBeNull()
    expect(lastSyncSucceeded('')).toBeNull()
  })

  it('没有失败段 → 成功', () => {
    expect(
      lastSyncSucceeded('共同步 12 个域账号：新建 1 个、更新 2 个、禁用 0 个、无变化 9 个')
    ).toBe(true)
  })

  it('含「失败 1 个」→ 失败', () => {
    expect(
      lastSyncSucceeded('共同步 12 个域账号：新建 1 个、更新 2 个、失败 1 个（详见服务端日志）')
    ).toBe(false)
  })

  it('「失败 0 个」不算失败 —— 否则每次同步都会被标红', () => {
    expect(lastSyncSucceeded('共同步 5 个域账号：新建 5 个、失败 0 个')).toBe(true)
  })

  it('数字与「失败」之间没有空格也能识别', () => {
    expect(lastSyncSucceeded('共同步 3 个域账号：失败12个')).toBe(false)
  })

  it('结尾恰好是「失败」二字（无数字）不算失败 —— 正则要求后面跟非零数字', () => {
    expect(lastSyncSucceeded('同步失败')).toBe(true)
  })
})
