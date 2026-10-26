package com.platform.app;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/customers")
@Validated
public class CustomerController {
  private final CustomerService service;
  private final ScoreClient scores;

  public CustomerController(CustomerService service, ScoreClient scores) {
    this.service = service;
    this.scores = scores;
  }

  @PostMapping
  public ResponseEntity<CustomerView> create(@Valid @RequestBody CustomerInput input) {
    var c = service.create(input);
    return ResponseEntity.created(URI.create("/api/be-api/customers/" + c.id())).body(c);
  }

  @PutMapping("/{id}")
  public CustomerView update(
      @PathVariable @Positive long id, @Valid @RequestBody CustomerInput input) {
    return service.update(id, input);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable @Positive long id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{id}")
  public CustomerView get(@PathVariable @Positive long id) {
    return service.get(id);
  }

  @GetMapping
  public Page<CustomerView> list(
      @RequestParam(required = false) CustomerStatus status,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return service.list(status, page, size);
  }

  @GetMapping("/search")
  public Page<CustomerView> search(
      @RequestParam @NotBlank @Size(max = 150) String name,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return service.search(name, page, size);
  }

  @GetMapping("/{id}/score")
  public ScoreClient.Score score(@PathVariable @Positive long id) {
    return scores.get(service.get(id).cpf());
  }
}
