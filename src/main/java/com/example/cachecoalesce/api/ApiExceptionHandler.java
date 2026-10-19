package com.example.cachecoalesce.api;

import com.example.cachecoalesce.cache.CoalescingCacheService.ItemNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ItemNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(ItemNotFoundException exception) {
        return new ErrorResponse(exception.getMessage());
    }

    public record ErrorResponse(String error) { }
}
