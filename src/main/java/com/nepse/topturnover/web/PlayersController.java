package com.nepse.topturnover.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.nepse.topturnover.analysis.BigPlayerService;

/** Big players: large-order, turnover and broker footprints, and which stage each stock is in. */
@Controller
public class PlayersController {
    private final BigPlayerService players;

    public PlayersController(BigPlayerService players) {
        this.players = players;
    }

    @GetMapping("/players")
    public String players(Model model) {
        model.addAttribute("active", "players");
        model.addAttribute("stages", BigPlayerService.Stage.values());
        model.addAttribute("brokerWindow", BigPlayerService.BROKER_WINDOW);
        model.addAttribute("brokerRecent", BigPlayerService.BROKER_RECENT);
        players.run().ifPresent(r -> model.addAttribute("r", r));
        return "players";
    }
}
