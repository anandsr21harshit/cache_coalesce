package com.example.cachecoalesce.api;

import com.example.cachecoalesce.cache.CoalescingCacheService;
import com.example.cachecoalesce.data.Item;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/items")
public class ItemController {
    private final CoalescingCacheService cacheService;
    public ItemController(CoalescingCacheService cacheService) { this.cacheService = cacheService; }

    @GetMapping("/{key}")
    public Item get(@PathVariable String key) { return cacheService.get(key); }
}
