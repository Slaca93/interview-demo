package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;

@SpringBootApplication
@RestController
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }

  @GetMapping("/api/hello")
  public String hello() {
    String pod = System.getenv("POD_NAME");
    String ip  = System.getenv("POD_IP");
    return "Hello from backenddd! pod=" + pod + " ip=" + ip;
  }
}
