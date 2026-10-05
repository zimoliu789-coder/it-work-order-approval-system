package com.enterprise.ticket.common.constant;

/**
 * 初始化相关配置键（本次新增）。
 *
 * <p>刻意用一个独立的常量类承载，而不是散落在 {@code SuperAdminInitializer} 或
 * {@code SystemConfigServiceImpl} 里：这两个类互相引用会造成「配置层 ↔ 业务层」的
 * 双向依赖，读代码的人会以为存在 Bean 级循环。放在 common 层则两边都只依赖它。
 */
public final class SetupKeys {

    private SetupKeys() {
    }

    /**
     * 内置超管的登录名（初始化那一刻固化，之后不可修改）。
     *
     * <p>归属于 {@code internal} 分组：该分组**不会**出现在「系统参数」页，
     * 因此管理员无法在界面上改掉它 —— 它是系统身份的一部分，不是可调参数。
     */
    public static final String KEY_SUPER_ADMIN_USERNAME = "super_admin_username";

    /**
     * 生产初始化清库的完成标记（一次性）。
     *
     * <p>清库成功后置位；此后即便环境变量仍打开也**不再执行** ——
     * 否则忘摘开关会让每次重启都清掉新产生的数据。
     */
    public static final String KEY_PROD_INIT_DONE = "prod_init_done";

    /** 内部配置分组名（系统参数页不渲染该分组） */
    public static final String GROUP_INTERNAL = "internal";
}
