package com.platform.app;

import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          Map<String, Object> realm = jwt.getClaimAsMap("realm_access");
          if (realm == null || !(realm.get("roles") instanceof Collection<?> roles))
            return List.of();
          return roles.stream()
              .filter(String.class::isInstance)
              .map(Object::toString)
              .filter(r -> r.equals("USER") || r.equals("ADMIN"))
              .<org.springframework.security.core.GrantedAuthority>map(
                  r -> new SimpleGrantedAuthority("ROLE_" + r))
              .toList();
        });
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                    a.requestMatchers(
                            "/health",
                            "/live",
                            "/version",
                            "/docs",
                            "/openapi.json",
                            "/actuator/**",
                            "/metrics",
                            "/prometheus")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/customers")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PUT, "/customers/*")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.DELETE, "/customers/*")
                    .hasRole("ADMIN")
                    .requestMatchers("/customers/events", "/customers/*/events")
                    .hasRole("ADMIN")
                    .requestMatchers("/customers", "/customers/**")
                    .hasAnyRole("USER", "ADMIN")
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
        .build();
  }
}
