/**
 * 蓝牙标签打印通道· Web Bluetooth + TSPL
 *
 * <h2>为什么这里必须把「限制」写清楚</h2>
 * 需求是「连接蓝牙标签打印机直接打印」。Web Bluetooth **做不到「通用」**，原因是硬性的：
 * <ol>
 *   <li><b>平台限制</b>：需要安全上下文（https / localhost）。Windows 的 Chrome / Edge 支持，
 *       Android Chrome 支持，**macOS 与 iOS 完全不支持** —— 这不是代码能绕过的。</li>
 *   <li><b>协议限制</b>：蓝牙标签机没有统一标准，必须知道 GATT 服务 / 写特征 UUID 与**指令集**
 *       （TSPL / CPCL / ESC-POS / ZPL，或厂商私有协议）。芝柯、汉印、佳博等常用机型多数接受
 *       TSPL，因此这里按 TSPL 实现；遇到私有协议的消费级机型（如部分精臣机型）则连不上。</li>
 * </ol>
 * 因此本模块的策略是：**优先标准服务 0xFF00 / 写特征 0xFF02；连不上就在该设备暴露的服务里
 * 找一个「可写特征」兜底；仍失败则把原因如实返回**，由界面提示改用浏览器打印 ——
 * 不做「静默失败」（用户会以为是打印机坏了）。
 */

import { ref, type Ref } from 'vue'

/** 通用 BLE 标签机最常见的服务与写特征（大量国产机型沿用这一对） */
export const PRINTER_SERVICE_UUID = 0xff00
export const PRINTER_WRITE_UUID = 0xff02

/**
 * 单次写入的字节数。
 *
 * <p>BLE 默认 MTU 只有 23 字节（有效载荷 20），一条 TSPL 指令远超这个量。
 * 不协商 MTU 的情况下也**不能一次写几百字节** —— 多数打印机会直接丢包，
 * 表现为「连上了但纸不动」。180 字节 + 20ms 间隔是兼容性最好的一档。
 */
const CHUNK_SIZE = 180
const CHUNK_DELAY_MS = 20

export interface BluetoothPrinterState {
  supported: boolean
  connected: boolean
  deviceName: string
  busy: boolean
  error: string
}

export interface BluetoothLabelPrinter {
  state: Ref<BluetoothPrinterState>
  connect: () => Promise<void>
  disconnect: () => void
  print: (tspl: string) => Promise<void>
  /** 重新探测当前浏览器是否支持 Web Bluetooth（每次打开弹窗都应调用一次） */
  refreshSupport: () => void
}

const sleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms))

/** 浏览器是否支持 Web Bluetooth（服务端渲染 / 老浏览器 / macOS 上为 false） */
export function isBluetoothSupported(): boolean {
  return typeof navigator !== 'undefined' && !!navigator.bluetooth
}

/**
 * 找一个可写特征。
 *
 * <p>先按通用打印机 UUID 找；找不到再遍历该设备暴露的所有服务，
 * 挑第一个带 `write` 或 `writeWithoutResponse` 的特征 ——
 * 这一步能救回一部分「服务 UUID 不是 0xFF00」的机型。
 */
async function resolveWriteCharacteristic(
  server: BluetoothRemoteGATTServer
): Promise<BluetoothRemoteGATTCharacteristic> {
  try {
    const service = await server.getPrimaryService(PRINTER_SERVICE_UUID)
    try {
      return await service.getCharacteristic(PRINTER_WRITE_UUID)
    } catch {
      const chars = await service.getCharacteristics()
      const writable = chars.find((c) => c.properties.write || c.properties.writeWithoutResponse)
      if (writable) {
        return writable
      }
    }
  } catch {
    // 落到下面的兜底遍历
  }

  const services = await server.getPrimaryServices()
  for (const service of services) {
    try {
      const chars = await service.getCharacteristics()
      const writable = chars.find((c) => c.properties.write || c.properties.writeWithoutResponse)
      if (writable) {
        return writable
      }
    } catch {
      // 个别服务不允许枚举特征，跳过
    }
  }
  throw new Error('这台设备上没找到可写的打印特征，可能不是标签打印机，或使用了私有协议。')
}

export function useBluetoothLabelPrinter(): BluetoothLabelPrinter {
  const state = ref<BluetoothPrinterState>({
    supported: isBluetoothSupported(),
    connected: false,
    deviceName: '',
    busy: false,
    error: ''
  })

  let device: BluetoothDevice | null = null
  let characteristic: BluetoothRemoteGATTCharacteristic | null = null

  function disconnect(): void {
    try {
      if (device?.gatt?.connected) {
        device.gatt.disconnect()
      }
    } catch {
      // 断开失败无需惊动用户
    }
    device = null
    characteristic = null
    state.value.connected = false
    state.value.deviceName = ''
  }

  async function connect(): Promise<void> {
    if (!state.value.supported) {
      state.value.error =
        '当前浏览器不支持蓝牙打印（需要 Windows 上的 Chrome / Edge，且页面走 https 或 localhost）。请改用「打印」按钮走系统打印。'
      throw new Error(state.value.error)
    }
    state.value.busy = true
    state.value.error = ''
    try {
      // 不用 filters（很多标签机不在广播包里带服务 UUID，过滤会导致列表是空的），
      // 改用 acceptAllDevices + optionalServices —— 让用户从完整列表里挑自己的打印机。
      device = await navigator.bluetooth.requestDevice({
        acceptAllDevices: true,
        optionalServices: [PRINTER_SERVICE_UUID]
      })
      device.addEventListener('gattserverdisconnected', () => {
        state.value.connected = false
        state.value.error = '蓝牙连接已断开，请重新连接后再打印。'
      })
      if (!device.gatt) {
        throw new Error('该设备不支持 GATT 连接。')
      }
      const server = await device.gatt.connect()
      characteristic = await resolveWriteCharacteristic(server)
      state.value.connected = true
      state.value.deviceName = device.name || '未命名设备'
    } catch (e) {
      disconnect()
      state.value.error = e instanceof Error ? e.message : String(e)
      throw e
    } finally {
      state.value.busy = false
    }
  }

  async function print(tspl: string): Promise<void> {
    if (!characteristic) {
      throw new Error('尚未连接蓝牙打印机。')
    }
    state.value.busy = true
    state.value.error = ''
    try {
      const bytes = new TextEncoder().encode(tspl)
      for (let offset = 0; offset < bytes.length; offset += CHUNK_SIZE) {
        const chunk = bytes.slice(offset, offset + CHUNK_SIZE)
        if (characteristic.properties.writeWithoutResponse) {
          await characteristic.writeValueWithoutResponse(chunk)
        } else {
          await characteristic.writeValue(chunk)
        }
        await sleep(CHUNK_DELAY_MS)
      }
    } catch (e) {
      state.value.error = e instanceof Error ? e.message : String(e)
      throw e
    } finally {
      state.value.busy = false
    }
  }

  /**
   * 重新探测浏览器能力。
   *
   * <p>「支持与否」在一次会话里确实不会变，但**组件是随页面一起挂载的** ——
   * 能力探测若只在 setup 时做一次，弹窗过很久才打开时用的就是当时的陈旧结论，
   * 降级分支（提示「不支持蓝牙打印」）永远不会被走到。因此每次打开弹窗重探一次。
   */
  function refreshSupport(): void {
    state.value.supported = isBluetoothSupported()
    if (!state.value.supported) {
      disconnect()
    }
  }

  return { state, connect, disconnect, print, refreshSupport }
}
