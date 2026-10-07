package com.lumileaf.lumi.controller;

import com.lumileaf.lumi.model.Admin;
import com.lumileaf.lumi.repository.AdminRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class SetupController {

    @Autowired
    private AdminRepository adminRepository;

    @Value("${app.setup-key}")
    private String setupKey;

    @GetMapping("/setup/create-superadmin")
    @ResponseBody
    public String createSuperAdmin(
            @RequestParam String key,
            @RequestParam String username,
            @RequestParam String password) {

        if (!setupKey.equals(key)) {
            return "Invalid setup key.";
        }

        boolean alreadyExists = adminRepository.findAll().stream()
                .anyMatch(a -> "SUPERADMIN".equalsIgnoreCase(a.getRole()));
        if (alreadyExists) {
            return "A Super Admin already exists. Setup is disabled.";
        }

        Admin admin = new Admin();
        admin.setUsername(username);
        admin.setPassword(password);
        admin.setRole("SUPERADMIN");
        admin.setDashboard("SuperAdmin");
        adminRepository.save(admin);

        return "Super Admin created. You can now log in with username: " + username;
    }
}