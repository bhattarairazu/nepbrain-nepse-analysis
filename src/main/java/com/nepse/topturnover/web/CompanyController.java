package com.nepse.topturnover.web;

import java.util.Comparator;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nepse.topturnover.config.NepseProperties;
import com.nepse.topturnover.repository.CompanyRepository;
import com.nepse.topturnover.service.CompanyService;

/** Data → Listed shares: reads each company's listed shares so the app can work out market cap. */
@Controller
public class CompanyController {
    private final CompanyService service;
    private final CompanyRepository repo;
    private final NepseProperties props;

    public CompanyController(CompanyService service, CompanyRepository repo, NepseProperties props) {
        this.service = service;
        this.repo = repo;
        this.props = props;
    }

    @GetMapping("/data/shares")
    public String page(Model model) {
        model.addAttribute("active", "shares");
        model.addAttribute("job", service.status());
        model.addAttribute("counts", repo.counts());
        model.addAttribute("pending", service.toFetch(false).size());
        model.addAttribute("delay", props.history().delaySeconds());
        model.addAttribute("missing", repo.all().values().stream().filter(s -> !"OK".equals(s.status()))
                .sorted(Comparator.comparing(CompanyRepository.Shares::symbol)).toList());
        return "data-shares";
    }

    @PostMapping("/data/shares/fetch")
    public String fetch(@RequestParam(defaultValue = "false") boolean everything, RedirectAttributes flash) {
        if (!service.start(everything)) flash.addFlashAttribute("message", "A listed-shares job is already running.");
        return "redirect:/data/shares";
    }

    @PostMapping("/data/shares/stop")
    public String stop() {
        service.stop();
        return "redirect:/data/shares";
    }
}
