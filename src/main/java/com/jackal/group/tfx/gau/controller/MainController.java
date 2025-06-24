package com.jackal.group.tfx.gau.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jasypt.encryption.StringEncryptor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@CrossOrigin(origins = "*")
public class MainController {
    @GetMapping("/health")
    public String health() { return "OK"; }
    @Resource
    @Qualifier("jasyptStringEncryptor")
    StringEncryptor stringEncryptor;

    @PostMapping("/encrypt")
    public String encrypt(@RequestBody String plainText) { return stringEncryptor.encrypt(plainText); }
}