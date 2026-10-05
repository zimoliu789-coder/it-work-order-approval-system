package com.enterprise.ticket.module.ad.dto;

import lombok.Data;

/**
 * DN 自动推导预览请求（；「系统自动处理」）
 *
 * <p>配置页在主表单里只露「域服务器地址 / 域管理员账号」两项，
 * 而真正落库的 {@code base_dn} / {@code bind_dn} 是由这两项推导出来的。
 * 本请求让前端能在维护人员输入时<b>实时展示系统将使用什么</b> ——
 * 否则「系统自动处理」就成了一句看不见的承诺：
 * 维护人员看不到推导结果，出错时也无从判断该不该去「高级选项」里覆盖。
 *
 * <p><b>刻意不带密码字段</b>：预览是纯计算，不需要、也不应该碰绑定密码。
 */
@Data
public class AdDnPreviewRequest {

    /** 域服务器地址（可含协议前缀 / 端口 / 多台，解析规则与保存时完全一致） */
    private String serverUrls;

    /** 维护人员填写的基础 DN；留空表示「请系统推导」 */
    private String baseDn;

    /** 维护人员填写的绑定账号（完整 DN / {@code company\query} / {@code query@company.com} / 裸账号） */
    private String bindDn;
}
