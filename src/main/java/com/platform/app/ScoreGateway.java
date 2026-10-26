package com.platform.app;

/** Port used by the application layer to obtain a customer's external score. */
public interface ScoreGateway {
  ScoreClient.Score get(String cpf);
}
