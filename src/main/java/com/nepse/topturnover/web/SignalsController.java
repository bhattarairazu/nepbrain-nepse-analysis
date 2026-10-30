package com.nepse.topturnover.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.nepse.topturnover.analysis.BacktestService;
import com.nepse.topturnover.analysis.Indicators;
import com.nepse.topturnover.analysis.PriceAdjuster;
import com.nepse.topturnover.analysis.SignalService;

/** The Signals page, and the small JSON feed behind the hover chart on every stock link. */
@Controller
public class SignalsController {
    private final SignalService signals;
    private final NamedParameterJdbcTemplate jdbc;
    private final BacktestService backtest;

    public SignalsController(SignalService signals, NamedParameterJdbcTemplate jdbc, BacktestService backtest) {
        this.signals = signals;
        this.jdbc = jdbc;
        this.backtest = backtest;
    }

    @GetMapping("/signals")
    public String signals(Model model) {
        model.addAttribute("active", "signals");
        signals.signals().ifPresent(s -> model.addAttribute("s", s));
        // the historical record of the rules behind each section (5-session hold), so signals are read with evidence
        backtest.run(5).ifPresent(r -> {
            Map<String, BacktestService.StrategyResult> rec = new LinkedHashMap<>();
            r.strategies().forEach(x -> rec.put(x.strategy().name(), x));
            model.addAttribute("record", rec);
            model.addAttribute("recordSessions", r.sessions());
        });
        return "signals";
    }

    /** OHLCV + 20/50-day EMA for the quick chart: {name, sector, dates[], o[], h[], l[], c[], v[], ema20[], ema50[]}. */
    @GetMapping("/api/chart/{symbol}")
    @ResponseBody
    public Map<String, Object> chart(@PathVariable String symbol, @RequestParam(defaultValue = "90") int days) {
        String sym = symbol.toUpperCase(Locale.ROOT);
        int n = Math.max(10, Math.min(days, 500));
        List<Object[]> rows = new ArrayList<>(jdbc.query("""
                SELECT trade_date, open_price, high_price, low_price, close_price, volume, prev_close FROM daily_price
                WHERE symbol = :s ORDER BY trade_date DESC LIMIT :n""",
                Map.of("s", sym, "n", n + 50),
                (rs, i) -> new Object[] {rs.getObject(1, LocalDate.class).toString(), rs.getDouble(2), rs.getDouble(3),
                        rs.getDouble(4), rs.getDouble(5), rs.getDouble(6), rs.getDouble(7)}));
        Collections.reverse(rows);
        // adjust for bonus/right shares so the chart has no artificial gaps
        double[][] cols = new double[6][rows.size()];
        for (int i = 0; i < rows.size(); i++) for (int k = 1; k <= 6; k++) cols[k - 1][i] = (double) rows.get(i)[k];
        PriceAdjuster.adjust(cols[0], cols[1], cols[2], cols[3], cols[4], cols[5]);
        for (int i = 0; i < rows.size(); i++) for (int k = 1; k <= 5; k++) rows.get(i)[k] = cols[k - 1][i];
        double[] close = rows.stream().mapToDouble(r -> (double) r[4]).toArray();
        List<Double> ema20 = new ArrayList<>(), ema50 = new ArrayList<>();
        for (int i = 0; i < close.length; i++) {
            ema20.add(Indicators.ema(close, 20, i + 1));
            ema50.add(Indicators.ema(close, 50, i + 1));
        }
        int from = Math.max(0, rows.size() - n);
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> info = jdbc.queryForList("SELECT name, sector FROM stock WHERE symbol = :s", Map.of("s", sym));
        out.put("symbol", sym);
        out.put("name", info.isEmpty() ? null : info.get(0).get("name"));
        out.put("sector", info.isEmpty() ? null : info.get(0).get("sector"));
        for (int k = 0; k < 6; k++) {
            final int col = k;
            out.put(new String[] {"dates", "o", "h", "l", "c", "v"}[k], rows.subList(from, rows.size()).stream().map(r -> r[col]).toList());
        }
        out.put("ema20", ema20.subList(from, ema20.size()));
        out.put("ema50", ema50.subList(from, ema50.size()));
        return out;
    }
}
