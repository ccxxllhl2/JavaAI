package com.jackal.group.tfx.gau.exception;

import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@Data
public class CustomBaseException extends RuntimeException {
    private int errCode;
    private String errMsg;
}