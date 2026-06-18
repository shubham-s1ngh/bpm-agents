# Camunda 8.6 Spring Boot Agent POC

## Tech Stack
- Runtime: Java 21 (Spring Boot 3.x)
- AI Framework: Spring AI (Ollama Local Instance)
- Network: Spring RestClient / OpenFeign to Camunda 8 REST API
- Camunda 8.6

## Architecture Decisions
- Use Spring AI's `@Tool` / `ChatClient` Function Calling API for LLM tools.
- Map all process key inputs explicitly to `Long` or `String` types to avoid overflow.
- Return structured Java records for errors instead of throwing raw runtime exceptions.

## Commands
- Build & Package: `./mvnw clean package`
- Run Application: `./mvnw spring-boot:run`
- Run Tests: `./mvnw test`