package com.lumileaf.lumi.controller;

import com.lumileaf.lumi.model.Admin;
import com.lumileaf.lumi.repository.AdminRepository;
import com.lumileaf.lumi.security.AdminUserDetails;
import com.lumileaf.lumi.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.beans.factory.annotation.Value;

import java.util.*;

@Controller
public class SuperAdminController {

    @Autowired private AdminRepository adminRepository;
    @Autowired private PermissionService permissionService;
    @Value("${database.admin.url}")
    private String databaseAdminUrl;

    private static final Map<String, String[]> MODULE_PERMISSION_KEYS = new LinkedHashMap<>();
    static {
        MODULE_PERMISSION_KEYS.put("Weighing", new String[]{
                "WEIGHING_UPLOAD_CSV", "WEIGHING_FINALIZE"
        });
        MODULE_PERMISSION_KEYS.put("Withering", new String[]{
                "WITHERING_SAVE"
        });
        MODULE_PERMISSION_KEYS.put("Rolling", new String[]{
                "ROLLING_SAVE", "DRYING_UPDATE"
        });
        MODULE_PERMISSION_KEYS.put("QA", new String[]{
                "QA_MASS_PRODUCTION_SET", "QA_GRADE_TRANSACTION", "QA_BLEND_BALANCE",
                "QA_MAKE_BLENDING", "QA_GENERATE_QR", "QA_ACCESS_PRODUCTION_TAB",
                "QA_EDIT_WEIGHING_RECORD", "QA_DELETE_WEIGHING_RECORD", "QA_BACKFILL_WEIGHING_RECORD"
        });
    }

    // NEW: fixed module -> username mapping, matching SecurityConfig's login redirect logic
    private static final Map<String, String> MODULE_TO_USERNAME = Map.of(
            "weighing", "admin1",
            "withering", "admin2",
            "rolling", "admin3",
            "qa", "admin"
    );

    // NEW: module -> dashboard URL
    private static final Map<String, String> MODULE_TO_DASHBOARD = Map.of(
            "weighing", "/mobile/waiting_dashboard",
            "withering", "/mobile/withering_dashboard",
            "rolling", "/mobile/rolling_dashboard",
            "qa", "/qa_dashboard"
    );

    private static final String SUPERADMIN_USERNAME = "Superadmin";

    @GetMapping("/admin/database")
    public String viewDatabase(HttpSession session) {
        if (!"SUPERADMIN".equals(session.getAttribute("role"))) {
            return "redirect:/login";
        }
        return "redirect:" + databaseAdminUrl;
    }

    @GetMapping("/superadmin")
    public String showSuperAdminDashboard(HttpSession session, Model model) {
        if (!"SUPERADMIN".equals(session.getAttribute("role"))) {
            return "redirect:/login";
        }

        List<Admin> allAdmins = adminRepository.findAll();

        Map<String, List<Admin>> adminsByModule = new LinkedHashMap<>();
        for (String module : MODULE_PERMISSION_KEYS.keySet()) {
            adminsByModule.put(module, new ArrayList<>());
        }

        for (Admin admin : allAdmins) {
            String dashboard = admin.getDashboard();
            if (dashboard != null && adminsByModule.containsKey(dashboard)) {
                adminsByModule.get(dashboard).add(admin);
            }
        }

        Map<Integer, Map<String, Boolean>> permissionStatusByAdminId = new HashMap<>();
        for (Admin admin : allAdmins) {
            String dashboard = admin.getDashboard();
            if (dashboard == null || !MODULE_PERMISSION_KEYS.containsKey(dashboard)) continue;

            Map<String, Boolean> statusMap = new LinkedHashMap<>();
            for (String key : MODULE_PERMISSION_KEYS.get(dashboard)) {
                statusMap.put(key, permissionService.hasPermission(admin, key));
            }
            permissionStatusByAdminId.put(admin.getId(), statusMap);
        }

        model.addAttribute("adminsByModule", adminsByModule);
        model.addAttribute("permissionStatusByAdminId", permissionStatusByAdminId);
        model.addAttribute("modulePermissionKeys", MODULE_PERMISSION_KEYS);

        return "superadmin_dashboard";
    }

    @PostMapping("/superadmin/permissions/toggle")
    public String togglePermission(
            @RequestParam Integer adminId,
            @RequestParam String permissionKey,
            HttpSession session) {

        if (!"SUPERADMIN".equals(session.getAttribute("role"))) {
            return "redirect:/login";
        }

        Admin admin = adminRepository.findById(adminId).orElse(null);
        if (admin == null) {
            return "redirect:/superadmin";
        }

        boolean currentlyGranted = permissionService.hasPermission(admin, permissionKey);
        if (currentlyGranted) {
            permissionService.revoke(adminId, permissionKey);
        } else {
            permissionService.grant(adminId, permissionKey);
        }

        return "redirect:/superadmin";
    }

    // ── NEW: impersonation ──────────────────────────────────────────

    @GetMapping("/superadmin/login-as/{module}")
    public String loginAsOfficer(@PathVariable String module,
                                 HttpSession session,
                                 HttpServletRequest request,
                                 HttpServletResponse response) {

        if (!"SUPERADMIN".equals(session.getAttribute("role"))) {
            return "redirect:/login";
        }

        String targetUsername = MODULE_TO_USERNAME.get(module);
        String targetDashboard = MODULE_TO_DASHBOARD.get(module);
        if (targetUsername == null || targetDashboard == null) {
            return "redirect:/superadmin";
        }

        Admin targetAdmin = adminRepository.findByUsername(targetUsername);
        if (targetAdmin == null) {
            return "redirect:/superadmin";
        }

        // Remember who the real Super Admin is, so exit-impersonation can restore them
        session.setAttribute("realSuperAdminUsername", SUPERADMIN_USERNAME);

        authenticateAs(targetAdmin, request, response, session);
        session.setAttribute("impersonating", true);

        return "redirect:" + targetDashboard;
    }

    @GetMapping("/superadmin/exit-impersonation")
    public String exitImpersonation(HttpSession session,
                                    HttpServletRequest request,
                                    HttpServletResponse response) {

        Object impersonating = session.getAttribute("impersonating");
        if (!(impersonating instanceof Boolean) || !((Boolean) impersonating)) {
            return "redirect:/superadmin";
        }

        String realSuperAdminUsername = (String) session.getAttribute("realSuperAdminUsername");
        Admin superAdmin = (realSuperAdminUsername != null)
                ? adminRepository.findByUsername(realSuperAdminUsername)
                : null;

        if (superAdmin == null) {
            session.invalidate();
            return "redirect:/login";
        }

        authenticateAs(superAdmin, request, response, session);
        session.removeAttribute("impersonating");
        session.removeAttribute("realSuperAdminUsername");

        return "redirect:/superadmin";
    }

    /** Builds a real Spring Security Authentication for the given admin and
     *  persists it to both the SecurityContext and the HTTP session, exactly
     *  like a normal form login would. */
    private void authenticateAs(Admin admin, HttpServletRequest request,
                                HttpServletResponse response, HttpSession session) {
        AdminUserDetails userDetails = new AdminUserDetails(admin);
        Authentication auth = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);

        session.setAttribute("username", admin.getUsername());
        session.setAttribute("role", admin.getRole());
    }
}