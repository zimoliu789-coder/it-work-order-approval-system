package com.enterprise.ticket.module.ad.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.ad.entity.AdConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * AD 配置 Mapper（{@code ad_config} 单行表）
 *
 * <p>不需要任何自定义 SQL：本表只有一行，读取用 {@code selectOne} /
 * {@code selectList} 取首条即可，不存在需要优化的查询形态。
 */
@Mapper
public interface AdConfigMapper extends BaseMapper<AdConfig> {
}
