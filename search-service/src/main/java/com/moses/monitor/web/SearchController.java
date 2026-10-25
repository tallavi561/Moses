package com.moses.monitor.web;

import com.moses.monitor.model.LogEntry;
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
    public List<LogEntry> search(@RequestParam List<String> words,
                                  @RequestParam Instant from,
                                  @RequestParam Instant to,
                                  @RequestParam(required = false) Integer logLevel) {
        return searchService.search(new SearchQuery(words, from, to, logLevel));
    }
}
