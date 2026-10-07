package com.lumileaf.lumi.service;

import com.lumileaf.lumi.model.Admin;
import com.lumileaf.lumi.model.AdminPermission;
import com.lumileaf.lumi.repository.AdminPermissionRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class PermissionService {

    @Autowired
    private AdminPermissionRepository adminPermissionRepository;

    public boolean hasPermission(Admin admin, String permissionKey) {
        if (admin == null || permissionKey == null) return false;

        // Super Admin impersonating an officer bypasses all permission checks,
        // without touching the real officer's granted/revoked rows in the DB.
        if (isImpersonating()) {
            return true;
        }

        return !adminPermissionRepository.existsByAdminIdAndPermissionKey(admin.getId(), permissionKey);
    }

    private boolean isImpersonating() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) return false;

        HttpSession session = attrs.getRequest().getSession(false);
        if (session == null) return false;

        Object flag = session.getAttribute("impersonating");
        return flag instanceof Boolean && (Boolean) flag;
    }

    public void revoke(Integer adminId, String permissionKey) {
        if (!adminPermissionRepository.existsByAdminIdAndPermissionKey(adminId, permissionKey)) {
            AdminPermission ap = new AdminPermission();
            ap.setAdminId(adminId);
            ap.setPermissionKey(permissionKey);
            adminPermissionRepository.save(ap);
        }
    }

    @Transactional
    public void grant(Integer adminId, String permissionKey) {
        adminPermissionRepository.deleteByAdminIdAndPermissionKey(adminId, permissionKey);
    }
}