package com.testgen.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "dashboard";
    }

    @GetMapping("/comparer")
    public String comparer() {
        return "comparer";
    }
}
