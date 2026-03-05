# AGENTS.md - Guidelines for AI Coding Agents

## Build Commands

```bash
# Build the project
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run all unit tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.example.myapplication.agent.AgentEngineTest"

# Run a single test method
./gradlew test --tests "com.example.myapplication.agent.AgentEngineTest.AgentAction_Click_should_have_correct_description"

# Run instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest

# Run lint checks
./gradlew lint

# Clean build
./gradlew clean
```

## Dependencies

This project uses Gradle with version catalogs (`gradle/libs.versions.toml`).

**When adding or modifying dependencies, use Context7** to:
- Find the correct library ID for the dependency
- Get the latest stable version
- Check for compatibility issues
- Find proper configuration examples

Add dependencies to `gradle/libs.versions.toml` using the `[libraries]` section, then reference them in `app/build.gradle.kts`.

## Code Style Guidelines

### Kotlin Style
- **Indentation**: 4 spaces
- **Line length**: 120 characters max
- **Trailing commas**: Use in multi-line declarations
- **Wildcard imports**: Avoid, use explicit imports

### Naming Conventions
- **Classes**: `PascalCase` (e.g., `AgentEngine`, `ZhipuApiClient`)
- **Functions/Variables**: `camelCase` (e.g., `processThinking`, `currentStep`)
- **Constants**: `UPPER_SNAKE_CASE` in companion objects (e.g., `MAX_RETRY_ATTEMPTS`)
- **Private fields**: No underscore prefix (e.g., `private val logger`)
- **Package names**: lowercase (e.g., `com.example.myapplication.agent`)

### Imports (in order)
1. Android framework imports
2. Kotlin stdlib imports
3. Third-party library imports
4. Project internal imports (use explicit imports, no wildcards)
5. Alias imports last (e.g., `import com.example.myapplication.config.AppConfig.Timeouts as TimeoutConfig`)

### Types & Null Safety
- Prefer `val` over `var`
- Use nullable types (`String?`) only when necessary
- Use `Result<T>` for operations that can fail
- Use sealed classes for state machines (e.g., `sealed class AgentLoopState`)
- Use `StateFlow` for observable state in ViewModels

### Error Handling
- Use `try/catch` with specific exceptions, avoid catching generic `Exception` when possible
- Log errors with `Logger.e()` before throwing or returning failure
- Use `Result.failure()` for recoverable errors
- Use `coroutineContext.ensureActive()` in suspend functions for cancellation checks
- Implement retry logic with exponential backoff for network calls

### Coroutines & Concurrency
- Use `Dispatchers.IO` for network and file operations
- Use `Dispatchers.Main` for UI updates
- Use `withContext()` to switch dispatchers
- Use `SupervisorJob()` for independent child jobs
- Check `isActive` before expensive operations in loops

### Logging
Use the `Logger` class consistently:
```kotlin
private val logger = Logger(TAG)
logger.d("Debug message")
logger.e("Error message", exception)
```

### Documentation
- Add KDoc for public classes and functions
- Document state machine transitions
- Include usage examples for complex components

## Architecture Patterns

### MVVM with Repository Pattern
- `ViewModel` exposes `StateFlow` for UI state
- `Repository` handles data operations
- `Dao` for Room database access

### Agent Pattern (ReAct)
The `AgentEngine` uses a state machine pattern:
- States: `Idle → Thinking → Acting → Observing → ... → Completed/Failed`
- Each state returns the next state
- Use immutable state objects

## Testing

### Unit Tests
- Use `runTest` from `kotlinx-coroutines-test` for coroutines
- Use `Truth` library for assertions (`assertThat()`)
- Use `Robolectric` for Android-dependent tests
- Mock external dependencies with `mockk` or `mockito`

### Test Naming
Use backtick notation for readable test names:
```kotlin
@Test
fun `initial queue should be empty`() { }
```

### Test Structure
- Use `@Before` for setup
- Group related tests with comment headers (`// ========== Enqueue Tests ==========`)
- Test both success and failure cases

## Project Structure

```
app/src/main/java/com/example/myapplication/
├── agent/          # Agent engine, tools, state management
├── api/            # API clients, models
├── accessibility/  # Android AccessibilityService
├── screen/         # Screen capture, image processing
├── data/           # Repository, DAOs, entities
├── ui/             # Jetpack Compose screens
├── utils/          # Logger, extensions, preferences
└── config/         # App configuration
```

## Important Notes

- **Context7**: Use for any dependency-related questions or issues
- **Chinese comments**: Existing codebase has Chinese comments, maintain consistency
- **Accessibility Service**: The app requires `AutoService` to be enabled for automation
- **Screen Capture**: Requires `MediaProjection` permission granted by user
