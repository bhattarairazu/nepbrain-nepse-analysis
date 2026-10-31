package com.nepse.topturnover.web;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nepse.topturnover.analysis.BrokerAnalysisService;
import com.nepse.topturnover.config.NepseProperties;
import com.nepse.topturnover.repository.FloorsheetRepository;
import com.nepse.topturnover.service.FloorsheetService;

/** Brokers page: floorsheet collection status, market-wide broker signals, one stock, one broker. */
@Controller
public class BrokersController {
    private final BrokerAnalysisService analysis;
    private final FloorsheetService collector;
    private final FloorsheetRepository floorsheet;
    private final NepseProperties props;

    public BrokersController(BrokerAnalysisService analysis, FloorsheetService collector, FloorsheetRepository floorsheet,
                             NepseProperties props) {
        this.analysis = analysis;
        this.collector = collector;
        this.floorsheet = floorsheet;
        this.props = props;
    }

    @GetMapping("/brokers")
    public String brokers(@RequestParam(defaultValue = "5") int sessions,
                          @RequestParam(required = false) String symbol,
                          @RequestParam(required = false) Integer broker,
                          Model model) {
        int n = List.of(1, 5, 10, 20).contains(sessions) ? sessions : 5;
        model.addAttribute("active", "brokers");
        model.addAttribute("sessions", n);
        model.addAttribute("collector", collector.status());
        model.addAttribute("collected", floorsheet.recent(10));
        BrokerAnalysisService.MarketView market = analysis.market(n);
        model.addAttribute("market", market);
        model.addAttribute("groups", List.of(
                java.util.Map.entry("Accumulation", market.accumulation()),
                java.util.Map.entry("Distribution", market.distribution()),
                java.util.Map.entry("Operator warning", market.operator())));
        model.addAttribute("signals", BrokerAnalysisService.Signal.values());
        if (symbol != null && !symbol.isBlank()) model.addAttribute("stock", analysis.stock(symbol.trim().toUpperCase(Locale.ROOT), n));
        if (broker != null) model.addAttribute("brokerView", analysis.broker(broker, n));
        model.addAttribute("symbol", symbol);
        model.addAttribute("broker", broker);
        return "brokers";
    }

    /** Manual collection, only after the close on a trading day (during trading the floorsheet is still growing). */
    @PostMapping("/brokers/collect")
    public String collect(RedirectAttributes flash) {
        ZonedDateTime now = ZonedDateTime.now(props.market().zone());
        boolean tradingDay = props.market().days().contains(now.getDayOfWeek());
        if (!tradingDay || now.toLocalTime().isBefore(props.crawl().start())) {
            flash.addFlashAttribute("message", "The floorsheet can be collected after " + props.crawl().start()
                    + " on a trading day, once the session has closed.");
        } else {
            flash.addFlashAttribute("message", collector.startIfNeeded(true)
                    ? "Collecting today's floorsheet in the background (a few minutes)." : "A collection is already running.");
        }
        return "redirect:/brokers";
    }
}
