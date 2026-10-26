package com.platform.app;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class HealthController {
  @GetMapping({"/health", "/live"})
  public Map<String, String> health() {
    return Map.of("status", "UP");
  }

  @GetMapping("/version")
  public Map<String, String> version() {
    return Map.of("service", "be-api", "version", "0.0.1");
  }
}
