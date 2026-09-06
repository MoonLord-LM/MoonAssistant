package cn.moonlord.mca.mark;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 控制台静态页路由：/ 与 /annotate 均转发到 static/index.html。
 */
@Controller
public class AnnotatePageController {

    @GetMapping({"/", "/annotate", "/annotate/"})
    public String annotate() {
        return "forward:/index.html";
    }
}
