package com.nepse.topturnover.crawler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import com.nepse.topturnover.config.AppConfig;

/** Builds the crawlers and decides the order to try them in (fixed or shuffled per run). */
public final class SourceSelector {
    private final AppConfig cfg;
    private final HttpFetcher http;
    private final Random random;

    public SourceSelector(AppConfig cfg, HttpFetcher http, Random random) {
        this.cfg = cfg;
        this.http = http;
        this.random = random;
    }

    /** @param only if non-null, use just this source (command-line override) */
    public List<SourceCrawler> order(String only) {
        List<String> names = new ArrayList<>(only != null ? List.of(only) : cfg.sources());
        if (only == null && cfg.sourceMode() == AppConfig.SourceMode.RANDOM) {
            Collections.shuffle(names, random);
        }
        List<SourceCrawler> out = new ArrayList<>();
        for (String n : names) out.add(create(n));
        return out;
    }

    private SourceCrawler create(String name) {
        return switch (name) {
            case SharesansarCrawler.NAME -> new SharesansarCrawler(http, cfg.sourceUrl(name));
            case MerolaganiCrawler.NAME -> new MerolaganiCrawler(http, cfg.sourceUrl(name), cfg.marketClose());
            default -> throw new IllegalArgumentException("Unknown source '" + name + "' (use sharesansar or merolagani)");
        };
    }
}
