package com.enterprise.ticket.module.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.device.entity.Device;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 设备台账 Mapper
 *
 * <p>为什么需要手写 SQL：设备采用逻辑删除（{@code deleted}），MyBatis-Plus 生成的查询会自动追加
 * {@code deleted = 0}。但 要求「资产编号全局唯一」且「存在历史业务记录的设备必须保留」，
 * 意味着<b>已软删除设备的资产编号也不允许被新设备复用</b>。因此在做唯一性校验与分类删除守卫时，
 * 必须绕过逻辑删除、统计全部历史行，这只能显式 SQL 表达。
 */
@Mapper
public interface DeviceMapper extends BaseMapper<Device> {

    /** 取该资产编号对应的记录 ID（含已软删除），无记录返回 null；用于「更新时排除自身」判断 */
    @Select("SELECT id FROM device WHERE asset_no = #{assetNo} ORDER BY id LIMIT 1")
    Long selectAnyIdByAssetNo(@Param("assetNo") String assetNo);

    /**
     * 统计引用了该分类的<b>全部</b>设备数（含已软删除）。
     * 分类存在外键引用（ON DELETE RESTRICT），因此删除分类前必须按「物理行」判断，
     * 否则软删除设备会让守卫放行，随后数据库外键报错变成 500。
     */
    @Select("SELECT COUNT(1) FROM device WHERE primary_category_id = #{categoryId} OR secondary_category_id = #{categoryId}")
    long countByCategoryIncludeDeleted(@Param("categoryId") Long categoryId);
}
