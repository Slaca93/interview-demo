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
    return "Hello from Java backend!";
  }
}
