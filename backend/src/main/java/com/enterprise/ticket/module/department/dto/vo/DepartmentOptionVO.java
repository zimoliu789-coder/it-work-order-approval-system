package com.enterprise.ticket.module.department.dto.vo;

import com.enterprise.ticket.module.department.entity.Department;
import lombok.Data;

/**
 * 部门扁平选项。
 *
 * <p>用于一切「选部门」的下拉：成员编辑里的归属部门、导入模板提示、
 * 以及后续批次里「指定部门成员」审批人规则的范围选择器。
 * 与 {@link DepartmentNodeVO} 分开：下拉要的是**扁平 + 带层级缩进**，
 * 树要的是 children 嵌套；把两者塞进一个 VO 会让每处调用都要判 null。
 */
@Data
public class DepartmentOptionVO {

    private Long id;

    private String deptName;

    private Long parentId;

    private Integer depth;

    private Boolean handlerGroup;

    /**
     * 带层级缩进的展示名，形如 {@code "研发部 / 前端一组"}。
     *
     * <p>在服务端拼好而不是前端拼：层级缩进的规则（缩进符、分隔符）
     * 一旦分散到多个下拉里就会各自漂移，而它恰恰是「这个部门在哪一层」的唯一线索。
     */
    private String displayPath;

    public static DepartmentOptionVO of(Department dept, String displayPath) {
        DepartmentOptionVO vo = new DepartmentOptionVO();
        vo.setId(dept.getId());
        vo.setDeptName(dept.getDeptName());
        vo.setParentId(dept.getParentId());
        vo.setDepth(dept.getDepth());
        vo.setHandlerGroup(Boolean.TRUE.equals(dept.getHandlerGroup()));
        vo.setDisplayPath(displayPath);
        return vo;
    }
}
