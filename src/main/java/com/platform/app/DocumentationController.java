package com.platform.app;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
public class DocumentationController {
  @GetMapping(value = "/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
  public Resource spec() {
    return new ClassPathResource("openapi.json");
  }

  @GetMapping(value = "/docs", produces = MediaType.TEXT_HTML_VALUE)
  public Resource docs() {
    return new ClassPathResource("docs.html");
  }
}
