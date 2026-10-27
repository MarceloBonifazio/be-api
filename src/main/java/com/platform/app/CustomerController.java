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
  private final ScoreGateway scores;

  public CustomerController(CustomerService service, ScoreGateway scores) {
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
      @RequestParam(defaultValue = "") @Size(max = 150) String query,
      @RequestParam(required = false) CustomerStatus status,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return query.isBlank() ? service.list(status, page, size)
        : service.search(query, status, page, size);
  }

  @GetMapping("/search")
  public Page<CustomerView> search(
      @RequestParam @NotBlank @Size(max = 150) String name,
      @RequestParam(required = false) CustomerStatus status,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return service.search(name, status, page, size);
  }

  @GetMapping("/summary")
  public CustomerService.Summary summary() {
    return service.summary();
  }

  @GetMapping("/events")
  public Page<CustomerActivity> events(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return service.events(null, page, size);
  }

  @GetMapping("/{id}/events")
  public Page<CustomerActivity> customerEvents(@PathVariable @Positive long id,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return service.events(id, page, size);
  }

  @GetMapping("/{id}/score")
  public ScoreView score(@PathVariable @Positive long id) {
    var customer = service.get(id);
    var result = scores.get(customer.cpf());
    var saved = service.recordScore(id, customer.cpf(), result);
    return new ScoreView(saved.cpf(), saved.score(), saved.classification(), saved.scoreQueriedAt());
  }

  public record ScoreView(String cpf, Integer score, String classification, java.time.Instant queriedAt) {}
}
