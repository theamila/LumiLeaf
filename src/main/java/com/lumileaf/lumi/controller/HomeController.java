package com.lumileaf.lumi.controller;

import com.lumileaf.lumi.model.Admin;
import com.lumileaf.lumi.repository.AdminRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class HomeController {

    @Autowired
    private AdminRepository adminRepository;

    @GetMapping("/")
    public String index() {
        return "redirect:/trace/notfound";
    }

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @PostMapping("/login")
    public String doLogin(@RequestParam String username,
                          @RequestParam String password,
                          HttpSession session,
                          Model model) {

        Admin admin = adminRepository.findByUsername(username);

        // 1. Basic Auth Check
        if (admin == null || !admin.getPassword().equals(password)) {
            model.addAttribute("error", "Invalid username or password");
            return "login";
        }

        // 2. FORCE REDIRECTS FOR TESTING (Bypasses DB data issues)
        if ("admin1".equals(username)) {
            session.setAttribute("username", "admin1");
            session.setAttribute("role", "USER");
            return "redirect:/mobile/waiting_dashboard";
        }

        if ("admin2".equals(username)) {
            session.setAttribute("username", "admin2");
            session.setAttribute("role", "USER");
            return "redirect:/mobile/withering_dashboard";
        }

        if ("admin3".equals(username)) {
            session.setAttribute("username", "admin3");
            session.setAttribute("role", "USER");
            return "redirect:/mobile/rolling_dashboard";
        }

        // Specific Force Override for your QA Admin
        if ("admin".equals(username)) {
            session.setAttribute("username", "admin");
            session.setAttribute("role", "QA");
            return "redirect:/qa_dashboard";
        }

        // 3. Normal Logic for other users
        session.setAttribute("username", admin.getUsername());
        session.setAttribute("role", admin.getRole());
        session.setAttribute("dashboard", admin.getDashboard());

        // Standardize strings (remove spaces and ignore case)
        String role = (admin.getRole() != null) ? admin.getRole().trim() : "";
        String target = (admin.getDashboard() != null) ? admin.getDashboard().trim() : "";

        System.out.println("LOGIN DEBUG: User [" + username + "] Role [" + role + "] Dashboard [" + target + "]");

        // 4. ROUTE QA USERS FIRST
        if (role.equalsIgnoreCase("QA")) {
            return "redirect:/qa_dashboard";
        }

        // 5. ROUTE MOBILE USERS
        if (target.equalsIgnoreCase("Waiting")) {
            return "redirect:/mobile/waiting_dashboard";
        } else if (target.equalsIgnoreCase("Withering")) {
            return "redirect:/mobile/withering_dashboard";
        } else if (target.equalsIgnoreCase("Rolling")) {
            return "redirect:/mobile/rolling_dashboard";
        } else {
            // If it hits here, the user exists but has no valid role or dashboard type
            return "redirect:/login?error=unknown_type";
        }
    }
}