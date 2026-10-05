package com.enterprise.ticket.module.system.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.log.AuditLog;
import com.enterprise.ticket.common.log.RiskLevel;
import com.enterprise.ticket.common.permission.BuiltinAdmin;
import com.enterprise.ticket.config.AppProperties;
import com.enterprise.ticket.module.system.dto.vo.SiteInfoVO;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.module.system.support.SiteBranding;
import com.enterprise.ticket.module.system.support.SiteLogoStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 站点品牌：系统名称与 logo（）。
 *
 * <h2>为什么「读」必须免认证</h2>
 * <p>登录页本身就要显示系统名称与 logo，而那一刻用户还没有会话。
 * 因此 {@code GET /site-info} 与 {@code GET /site-logo/image} 进了
 * {@code app.security.permit-all}（见 application.yml）。
 * 它们的暴露面很窄：只有「系统叫什么」「logo 长什么样」，不含任何用户或业务数据。
 *
 * <h2>为什么「写」用独立路径而不是 configs 的 PUT</h2>
 * <p>系统名称确实可以走既有的 {@code PUT /api/system/configs}（它是普通文本参数），
 * 但 logo 是<b>文件</b>：需要先落盘、再回写一个引用值，还可能要在替换时删掉旧文件。
 * 这段流程放进「批量保存参数」会让那个接口承担两类完全不同的语义。
 * 因此 logo 的读写单列在这里；而 system 名称的编辑仍在参数页
 * （见 {@code SystemConfigServiceImpl#updateValues} 的 admin-only 闸门）。
 *
 * <h2>为什么写入口要做内置超管判定</h2>
 * <p>{@code @PreAuthorize("@perm.has('config:manage')")} 只保证「是超管」——
 * 而「其他超管」（如演示账号张伟）也是超管，能通过这道方法级校验。
 * 需求要求这两项只归内置超管，所以必须再判一次；
 * 少了这一步，「其他超管只读」就只是前端置灰的观感，curl 直连即可绕过。
 *
 * <p>测试路径：
 * <pre>
 * GET    http://localhost:8080/api/system/site-info
 * GET    http://localhost:8080/api/system/site-logo/image
 * POST   http://localhost:8080/api/system/site-logo      （multipart: file）
 * DELETE http://localhost:8080/api/system/site-logo      （恢复默认「IT」文字图标）
 * </pre>
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SiteInfoController {

    /** 图片 logo 的固定访问地址：不带版本号，前端靠 Cache-Control 与查询参数避免拿到旧图 */
    private static final String LOGO_IMAGE_URL = "/api/system/site-logo/image";

    private final SystemConfigService systemConfigService;

    private final SiteLogoStorage logoStorage;

    private final AppProperties appProperties;

    /**
     * 站点品牌信息（免认证）。
     *
     * <p>测试路径：GET http://localhost:8080/api/system/site-info
     */
    @GetMapping("/site-info")
    public ApiResponse<SiteInfoVO> siteInfo() {
        return ApiResponse.success(currentSiteInfo());
    }

    /**
     * logo 图片本体（免认证）。
     *
     * <p>当前配置为文字 logo 时返回 404 —— 前端在 {@code logoType=TEXT} 时本就不会请求它，
     * 这个 404 是「有人手敲地址」时的正确答案，而不是静默返回空白图。
     */
    @GetMapping("/site-logo/image")
    public ResponseEntity<Resource> logoImage() {
        String fileName = SiteBranding.logoFileName(systemConfigService.siteLogoRaw());
        if (fileName == null) {
            throw new BusinessException(ErrorCode.SITE_LOGO_NOT_FOUND);
        }
        Path file = logoStorage.resolve(fileName);
        if (!Files.isReadable(file)) {
            throw new BusinessException(ErrorCode.SITE_LOGO_NOT_FOUND);
        }
        String contentType = logoStorage.contentType(fileName);
        return ResponseEntity.ok()
                // 渲染类型只认服务端按扩展名推断的结果，不采用上传时客户端声明的 contentType
                // （否则可把任意文件声明成 image/svg+xml，在同源下渲染 SVG 即构成存储型 XSS）
                .contentType(contentType == null
                        ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType(contentType))
                .header("X-Content-Type-Options", "nosniff")
                // no-store 而不是 max-age：需求明确要求「改完立即全局生效」。
                // 允许浏览器缓存会让管理员换完 logo 仍看到旧图，且刷新也不一定好 ——
                // 与「改了没生效」的 bug 无法区分。logo 只有几十 KB，不值得为此冒险。
                .cacheControl(CacheControl.noStore())
                .body(new FileSystemResource(file));
    }

    /**
     * 上传 logo 图片（仅内置超级管理员）。
     *
     * <p>替换语义：新图落盘成功后，把旧图从磁盘删掉（DB 侧取值随之改写）——
     * 否则每换一次 logo 就在磁盘上留一份永远不会被引用的文件。
     */
    @PostMapping("/site-logo")
    @PreAuthorize("@perm.has('config:manage')")
    @AuditLog(module = "SYSTEM", action = "SITE_LOGO_UPLOAD", description = "上传站点 logo", risk = RiskLevel.HIGH)
    public ApiResponse<SiteInfoVO> uploadLogo(@RequestParam("file") MultipartFile file) {
        requireBuiltinAdmin();
        logoStorage.validate(file);

        String previous = systemConfigService.siteLogoRaw();
        String storedPath = logoStorage.store(file);
        systemConfigService.updateValue(SiteBranding.KEY_SITE_LOGO, SiteBranding.fileLogoValue(storedPath));

        // 删除旧图放在写库之后：先落新、再改库、最后删旧。
        // 反过来（先删旧）一旦新图写库失败，就会得到一个「库里有引用、磁盘没文件」的裂图状态。
        String previousFile = SiteBranding.logoFileName(previous);
        if (previousFile != null && !previousFile.equals(storedPath)) {
            logoStorage.deleteQuietly(previousFile);
        }
        return ApiResponse.success("logo 已更新", currentSiteInfo());
    }

    /**
     * 清除图片 logo，恢复默认文字图标「IT」（仅内置超级管理员）。
     */
    @DeleteMapping("/site-logo")
    @PreAuthorize("@perm.has('config:manage')")
    @AuditLog(module = "SYSTEM", action = "SITE_LOGO_RESET", description = "恢复默认站点 logo", risk = RiskLevel.HIGH)
    public ApiResponse<SiteInfoVO> resetLogo() {
        requireBuiltinAdmin();

        String previous = systemConfigService.siteLogoRaw();
        systemConfigService.updateValue(SiteBranding.KEY_SITE_LOGO, SiteBranding.DEFAULT_LOGO_TEXT);

        String previousFile = SiteBranding.logoFileName(previous);
        if (previousFile != null) {
            logoStorage.deleteQuietly(previousFile);
        }
        return ApiResponse.success("已恢复默认 logo", currentSiteInfo());
    }

    /**
     * 组装当前品牌信息。
     *
     * <p>「文字 / 图片」的判定只在这一处发生，避免每个渲染点各判一遍。
     * 版权文字（）同样在这一处装配：它允许为空串，前端据此决定不渲染页脚那一行。
     */
    private SiteInfoVO currentSiteInfo() {
        String siteName = systemConfigService.siteName();
        String copyright = systemConfigService.siteCopyright();
        String rawLogo = systemConfigService.siteLogoRaw();
        String fileName = SiteBranding.logoFileName(rawLogo);
        if (fileName == null) {
            return SiteInfoVO.of(siteName, copyright, "TEXT", rawLogo, null);
        }
        return SiteInfoVO.of(siteName, copyright, "IMAGE", null, LOGO_IMAGE_URL);
    }

    /** 站点品牌仅内置超管可改：其他超管（同为 super_admin 角色）也必须在服务端被挡住 */
    private void requireBuiltinAdmin() {
        if (!BuiltinAdmin.isCurrentUserBuiltinAdmin(appProperties)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "仅内置超级管理员 " + BuiltinAdmin.username(appProperties) + " 可修改系统名称、图标与版权文字");
        }
    }
}
