package com.lumileaf.lumi.config;

import com.lumileaf.lumi.model.Admin;
import com.lumileaf.lumi.repository.AdminRepository;
import com.lumileaf.lumi.security.AdminUserDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public UserDetailsService userDetailsService(AdminRepository adminRepository) {
        return username -> {
            Admin admin = adminRepository.findByUsername(username);
            if (admin == null) {
                throw new UsernameNotFoundException("No admin found with username: " + username);
            }
            return new AdminUserDetails(admin);
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return NoOpPasswordEncoder.getInstance();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers(new AntPathRequestMatcher("/api/**"))
                )

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/", "/index", "/login", "/trace/**",
                                "/manifest.json", "/sw.js",
                                "/icons/**", "/css/**", "/js/**", "/images/**", "/uploads/**", "/static/**",
                                "/api/supplier/*/detail", "/api/production/*/contributions"
                        ).permitAll()
                        .anyRequest().authenticated()
                )


                .formLogin(form -> form
                        .loginPage("/login").permitAll()
                        .successHandler((request, response, authentication) -> {
                            String username = authentication.getName();
                            String role = authentication.getAuthorities().iterator().next().getAuthority();

                            // Set BOTH username and role in session
                            request.getSession().setAttribute("username", username);
                            request.getSession().setAttribute("role", role.replace("ROLE_", ""));  // Strip "ROLE_" prefix

                            if ("admin1".equals(username)) {
                                response.sendRedirect("/mobile/waiting_dashboard");
                            } else if ("admin2".equals(username)) {
                                response.sendRedirect("/mobile/withering_dashboard");
                            } else if ("admin3".equals(username)) {
                                response.sendRedirect("/mobile/rolling_dashboard");
                            } else if (role.contains("QA") || "admin".equals(username)) {
                                response.sendRedirect("/qa_dashboard");
                            } else {
                                response.sendRedirect("/login?error=unknown_type");
                            }
                        })
                )
                .logout(logout -> logout.permitAll());

        return http.build();
    }
}