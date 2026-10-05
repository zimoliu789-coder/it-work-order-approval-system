package com.enterprise.ticket.module.system.dto.vo;

import com.enterprise.ticket.module.system.entity.ExceptionLog;
import lombok.Data;

/**
 * 异常日志详情—— 列表行 + 完整堆栈。
 *
 * <p>堆栈单独一个接口而不是随列表下发：一次事故可能上万行，
 * 把堆栈塞进列表响应会让接口变成几十 MB（而用户只会点开其中一行看）。
 */
@Data
public class ExceptionLogDetailVO {

    private ExceptionLogVO item;

    /** 完整堆栈（落库时截断到 4000 字符） */
    private String stackTrace;

    public static ExceptionLogDetailVO of(ExceptionLog entity) {
        ExceptionLogDetailVO vo = new ExceptionLogDetailVO();
        vo.setItem(ExceptionLogVO.of(entity));
        vo.setStackTrace(entity.getStackTrace());
        return vo;
    }
}
