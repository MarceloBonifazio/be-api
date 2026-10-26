package com.platform.app;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
  final HttpStatus status;

  ApiException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }
}
