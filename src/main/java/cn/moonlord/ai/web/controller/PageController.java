package cn.moonlord.ai.web.controller;

import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class PageController {

    @Autowired
    private APIController apiController;

    @SneakyThrows
    @RequestMapping("/")
    public String index(Model model) {
        model.addAttribute("performance", apiController.performance());
        return "index";
    }

}
