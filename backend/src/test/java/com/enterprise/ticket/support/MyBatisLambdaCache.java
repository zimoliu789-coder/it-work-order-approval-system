package com.enterprise.ticket.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 单元测试用的 MyBatis-Plus 实体元数据注册器。
 *
 * <p>生产代码普遍使用 {@code Wrappers.<T>lambdaQuery().eq(T::getId, ...)} 这类「方法引用」写法，
 * 它依赖 MyBatis-Plus 的 Lambda 缓存（由 Spring 启动时扫描实体建立）。纯 Mockito 单元测试
 * 不启动 Spring 上下文，因此求值方法引用时会抛
 * {@code MybatisPlusException: can not find lambda cache for this entity}。
 *
 * <p>在测试的 {@code @BeforeAll} 中调用本类注册用到的实体即可，无需引入数据库或 Spring。
 */
public final class MyBatisLambdaCache {

    private MyBatisLambdaCache() {
    }

    /**
     * 为给定实体注册表信息（幂等，重复调用安全）。
     *
     * <p>只需注册「测试路径上会被构造 Wrapper 的实体」，不必注册全部实体。
     */
    public static void init(Class<?>... entities) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.enterprise.ticket.support.unit-test");
        for (Class<?> entity : entities) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }
}
