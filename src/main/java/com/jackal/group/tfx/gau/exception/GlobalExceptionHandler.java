package com.jackal.group.tfx.gau.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(CustomBaseException.class)
    public ResponseEntity handleCustomBaseException(CustomBaseException ex) {
        return ResponseEntity.status(ex.getErrCode()).body(ex.getErrMsg());
    }
}