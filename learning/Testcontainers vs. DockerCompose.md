# TestContainers vs Docker Compose - When to Use What

## TestContainers (`TestcontainersConfiguration`)
**Purpose: Development & Testing**

### Advantages ✅
- **Zero Setup**: Starts automatically when you run the app
- **Self-Contained**: Everything in Java code, no external files
- **Test Integration**: Perfect for unit/integration tests
- **Auto-Cleanup**: Containers destroyed when app stops
- **Port Management**: Automatically finds free ports
- **Isolation**: Each test run gets fresh containers

### Use Cases
- Local development (`./gradlew bootRun`)
- Running tests (`./gradlew test`)
- CI/CD pipelines
- Quick prototyping

### Code Example
```java
@Bean
@ServiceConnection  // Magic! Auto-configures Spring Boot
public PostgreSQLContainer<?> postgresContainer() {
    return new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("filemanager")
            .withReuse(true);  // Reuse across test runs
}
```

---

## Docker Compose (`docker-compose.yml`)
**Purpose: Production-like Environment**

### Advantages ✅
- **Persistent**: Services keep running between app restarts
- **Realistic**: More like production environment
- **Debugging**: Can inspect containers, logs, databases
- **Performance**: No startup overhead
- **Team Sharing**: Same environment for whole team
- **External Access**: Can connect external tools (pgAdmin, etc.)

### Use Cases
- Integration testing with external tools
- Demo environments
- Production deployment
- When you need persistent data between runs
- Debugging complex scenarios

### Code Example
```yaml
services:
  postgres:
    image: postgres:15-alpine
    ports:
      - "5432:5432"  # Accessible from host
    volumes:
      - postgres_data:/var/lib/postgresql/data  # Persistent!
```

---

## Decision Matrix

| Scenario | Use | Command |
|----------|-----|---------|
| Quick development | TestContainers | `./gradlew bootRun` |
| Running tests | TestContainers | `./gradlew test` |
| Debugging DB issues | Docker Compose | `docker-compose up -d postgres` |
| Team demo | Docker Compose | `docker-compose up` |
| CI/CD | TestContainers | Automatic in pipeline |
| Production | Neither | Real infrastructure |

---

## The Magic of @ServiceConnection

The really clever part is this annotation:

```java
@Bean
@ServiceConnection  // ⭐ This is the magic!
public PostgreSQLContainer<?> postgresContainer() {
    // TestContainers automatically configures:
    // - spring.r2dbc.url
    // - spring.r2dbc.username  
    // - spring.r2dbc.password
    // No manual configuration needed!
}
```

This means your `application.yml` database settings are **automatically overridden** when using TestContainers.