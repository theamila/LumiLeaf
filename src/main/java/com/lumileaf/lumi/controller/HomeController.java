package com.lumileaf.lumi.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public ResponseEntity<Void> index() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }
}