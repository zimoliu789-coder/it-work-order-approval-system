/**
 * 流程编码的工具函数（ · W4-B）。
 *
 * <p>为什么单独成文件：这里的 `32` 是**后端库列宽**（`approval_flow.flow_code VARCHAR(32)`，
 * 编码格式正则同时限制在 2–32 位）的镜像。把它写进 `.vue` 里就没人会去测它，
 * 而它一旦写错，用户看到的是"复制对话框预填的编码提交后被后端判为非法"这种自相矛盾的提示。
 * 抽出来之后 `FLOW_CODE_MAX_LENGTH` 有名字、有单测，改动时也有地方可以查。
 */

/** 流程编码长度上限，与后端 `approval_flow.flow_code` 列宽 / 编码格式正则一致 */
export const FLOW_CODE_MAX_LENGTH = 32

/** 复制产物的编码后缀 */
const COPY_SUFFIX = '_COPY'

/**
 * 「复制」对话框里的编码预填建议：`{源编码}_COPY`，并按上限**截断**。
 *
 * <p>截断不是可选项：源编码本身就可能已经用满 32 位（后端允许），加后缀必然溢出。
 *
 * <p>刻意只做"截断 + 加后缀"，**不查重** —— 唯一性由后端判定。
 * 前端若也去算一遍可用编码，就多出一个事实源，两边不一致时会互相打脸。
 *
 * @param sourceCode 源流程编码；为空时返回空串（让后端按源编码派生，而不是预填一个非法值）
 */
export function suggestCopyCode(sourceCode: string): string {
  const code = (sourceCode ?? '').trim()
  if (!code) {
    return ''
  }
  return code.slice(0, FLOW_CODE_MAX_LENGTH - COPY_SUFFIX.length) + COPY_SUFFIX
}
