package com.moses.monitor.web;

import com.moses.monitor.model.LogEntry;
import com.moses.monitor.search.LogPage;
import com.moses.monitor.search.SearchQuery;
import com.moses.monitor.search.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public List<LogEntry> search(@RequestParam(required = false) List<String> words,
                                  @RequestParam Instant from,
                                  @RequestParam Instant to,
                                  @RequestParam(required = false) Integer logLevel,
                                  @RequestParam(required = false) String service) {
        return searchService.search(new SearchQuery(words, from, to, logLevel, service));
    }

    @GetMapping("/page")
    public LogPage searchPage(@RequestParam(required = false) List<String> words,
                               @RequestParam Instant from,
                               @RequestParam Instant to,
                               @RequestParam(required = false) Integer logLevel,
                               @RequestParam(required = false) String service,
                               @RequestParam(required = false) Instant before,
                               @RequestParam(required = false) String beforeId,
                               @RequestParam(defaultValue = "100") int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        return searchService.searchPage(new SearchQuery(words, from, to, logLevel, service),
                before, beforeId, boundedLimit);
    }
}
