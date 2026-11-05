package com.unichem.inventory.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the Angular build (copied to /static) for client-side routes, when deployed as one jar. */
@Controller
public class SpaController {
    @GetMapping({"/", "/login", "/dashboard", "/inventory/**", "/actions/**", "/matrix", "/years", "/masters/**", "/import", "/users/**", "/audit", "/profile", "/data"})
    public String index() { return "forward:/index.html"; }
}
