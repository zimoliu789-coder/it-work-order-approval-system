package com.enterprise.ticket.module.attachment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.attachment.entity.Attachment;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 附件 Mapper
 *
 * <p>绝大多数查询由 {@code BaseMapper} 满足：附件查询都是「按 biz_type + biz_id + deleted」或主键，
 * 逻辑删除由 {@code @TableLogic} 全局处理，无需手写 SQL。
 *
 * <p>例外是下面两个方法 —— 它们<b>必须绕过逻辑删除</b>，因此不能由 BaseMapper 表达
 * （与 {@code DeviceMapper} 里「asset_no 唯一性预检要看到已删行」同一类场景）：
 * <ul>
 *   <li>{@link #selectSoftDeletedBefore}：清理任务要处理的恰恰是 {@code deleted = 1} 的行，
 *       而这些行会被 {@code @TableLogic} 自动过滤掉，用 BaseMapper 永远查不到；</li>
 *   <li>{@link #hardDeleteById}：清理任务要做的是<b>物理删除</b>（彻底回收表空间），
 *       而 {@code BaseMapper#deleteById} 是逻辑删除 —— 调用它等于什么都没做，
 *       表现为「任务日志说清理了 N 个，第二天还是 N 个」。</li>
 * </ul>
 */
public interface AttachmentMapper extends BaseMapper<Attachment> {

    /**
     * 查询「已软删且超过保留期」的附件（ 保留期清理）
     *
     * <p>手写 SQL 且<b>不带</b> {@code deleted = 0} 条件，正是为了拿到逻辑删除的行 ——
     * 这是清理任务的输入，不是遗漏。
     *
     * @param deadline 截止时刻：{@code deleted_at < deadline} 的视为超期
     * @param limit    单次上限，避免一次大删拖垮磁盘
     */
    @Select("SELECT * FROM attachment "
            + "WHERE deleted = 1 AND deleted_at IS NOT NULL AND deleted_at < #{deadline} "
            + "ORDER BY id ASC LIMIT #{limit}")
    List<Attachment> selectSoftDeletedBefore(@Param("deadline") LocalDateTime deadline,
                                             @Param("limit") int limit);

    /**
     * 物理删除附件行（不可逆）
     *
     * <p>只用于保留期清理：逻辑删除早已发生，此处是超过保留期后的彻底回收。
     * 磁盘文件由调用方先行删除（清理任务保证「先盘后库」，避免出现
     * 「记录没了、文件还在」这种谁都找不到的孤儿）。
     */
    @Delete("DELETE FROM attachment WHERE id = #{id}")
    int hardDeleteById(@Param("id") Long id);
}
