# Module 2 — Pipeline YAML Configuration & Validation

**DevOps CI/CD Automation Platform — Control Plane**

| Attribute | Value |
|-----------|-------|
| Module | 2 |
| Name | Pipeline YAML Configuration & Validation |
| Base Package | `com.cicd.platform.controlplane` |
| Repository Root | `backend/` |
| Java | 21 |
| Spring Boot | 3.3.5 |
| YAML Parser | SnakeYAML 2.x (transitive via spring-boot-starter-web) |
| Test DB | H2 (in-memory) |
| Build | Maven |
| Tests | JUnit 5 + Mockito + MockMvc |

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Module Objectives](#2-module-objectives)
3. [Scope](#3-scope)
4. [YAML Schema Definition](#4-yaml-schema-definition)
5. [Architecture Overview](#5-architecture-overview)
6. [Config Model (YAML → Java POJOs)](#6-config-model-yaml--java-pojos)
7. [Parser Layer](#7-parser-layer)
8. [Validation Architecture](#8-validation-architecture)
9. [Schema Validator](#9-schema-validator)
10. [Semantic Validator](#10-semantic-validator)
11. [Dependency Validator](#11-dependency-validator)
12. [Domain Mapper](#12-domain-mapper)
13. [Service Layer](#13-service-layer)
14. [REST API Endpoints](#14-rest-api-endpoints)
15. [DTOs](#15-dtos)
16. [Exception Handling](#16-exception-handling)
17. [End-to-End Request Flow](#17-end-to-end-request-flow)
18. [Testing Architecture](#18-testing-architecture)
19. [Configuration](#19-configuration)
20. [Build and Verification](#20-build-and-verification)
21. [Module Dependencies](#21-module-dependencies)
22. [What Module 2 Enables](#22-what-module-2-enables)
23. [Next Module](#23-next-module)

---

## 1. Executive Summary

Module 2 adds **YAML-based pipeline configuration ingestion and multi-level validation** to the CI/CD control plane. It enables users to submit pipeline definitions as structured YAML documents through the REST API, which are then parsed, validated across three distinct levels (schema, semantic, and dependency), and persisted as versioned `PipelineVersion` records.

A CI/CD control plane requires YAML configuration support because pipeline definitions are the primary interface through which development teams express their build, test, scan, and deployment workflows. YAML is the industry standard for declarative pipeline configuration (used by GitHub Actions, GitLab CI, Jenkins, Azure Pipelines), and providing robust validation ensures that only correct, unambiguous, and executable pipeline definitions enter the system.

Module 2 intentionally implements **only YAML parsing, validation, and versioned persistence**. It does not implement pipeline execution, worker dispatch, Docker builds, or any runtime orchestration. The YAML content is stored as-is in `PipelineVersion.yamlContent`, and the structural elements (stages, jobs) are parsed for validation purposes only — Module 3 will use these definitions to instantiate runtime `PipelineStage` and `PipelineJob` entities during execution.

---

## 2. Module Objectives

| # | Objective | Architectural Rationale |
|---|-----------|------------------------|
| 1 | Ingest pipeline definitions as YAML via REST API | Provides the standard interface for pipeline configuration submission |
| 2 | Parse YAML safely using SnakeYAML 2.x with type-safe mapping | Prevents YAML deserialization attacks while providing structured Java objects |
| 3 | Validate pipeline YAML at three levels (schema, semantic, dependency) | Catches progressively deeper errors at each level, failing fast |
| 4 | Map validated YAML configuration to domain entities | Bridges the gap between configuration format and persistent domain model |
| 5 | Persist YAML as versioned pipeline versions | Supports audit trail, rollback, and configuration history |
| 6 | Auto-create pipelines from YAML submission | Reduces API friction by combining pipeline creation with YAML submission |
| 7 | Provide structured validation error responses | Enables clients to programmatically handle specific validation failures |
| 8 | Integrate with existing Module 1 domain model | Reuses existing `Pipeline`, `PipelineVersion`, and repository infrastructure |

---

## 3. Scope

### In Scope

- 4 YAML config model POJOs (`PipelineRoot`, `PipelineConfig`, `StageConfig`, `JobConfig`)
- SnakeYAML-based parser with SafeConstructor for secure deserialization
- 3 validator classes implementing a 3-level validation pipeline
- Domain mapper converting YAML config to domain entities
- Service layer orchestrating parse → validate → persist workflow
- 2 new REST API endpoints for YAML submission
- 2 new DTOs (request and error response)
- Global exception handler integration for `PipelineValidationException`
- 6 unit test classes (parser, 3 validators, mapper, service)
- 1 integration test class (API endpoint tests with MockMvc)
- 97 total tests (45 Module 1 + 52 Module 2)

### Out of Scope

- Pipeline execution and worker orchestration (Module 3+)
- YAML template variables or parameter substitution
- YAML includes or multi-document support
- Pipeline version diff/comparison UI
- YAML syntax highlighting or linting in the API
- Authentication/authorization on endpoints (future module)
- RabbitMQ, Docker, Azure integration

---

## 4. YAML Schema Definition

The supported YAML schema follows a declarative pipeline structure:

```yaml
pipeline:
  name: my-ci-pipeline                    # Required, ≤255 chars
  description: Build and test my app      # Optional
  stages:                                 # Required, ≥1 stage
    - name: build                         # Required, unique, ≤255 chars
      dependsOn: []                       # Optional, references other stage names
      jobs:                               # Required, ≥1 job per stage
        - name: compile                   # Required, unique within stage, ≤255 chars
          type: BUILD                     # Required, enum: BUILD|TEST|SCAN|DEPLOY|PACKAGE|CUSTOM
          dependsOn: []                   # Optional, references other job names within same stage

    - name: test
      dependsOn:
        - build                           # Stage-level dependency: test runs after build
      jobs:
        - name: unit-test
          type: TEST
        - name: integration-test
          type: TEST
          dependsOn:
            - unit-test                   # Job-level dependency within stage

    - name: scan
      dependsOn:
        - build
      jobs:
        - name: sast
          type: SCAN
        - name: container-scan
          type: SCAN

    - name: deploy
      dependsOn:
        - test
        - scan
      jobs:
        - name: staging-deploy
          type: DEPLOY
```

### Schema Rules

| Rule | Level | Description |
|------|-------|-------------|
| Top-level `pipeline` key required | Schema | YAML must have `pipeline:` as the root key |
| `pipeline.name` required | Schema | Pipeline name must be non-blank |
| `pipeline.name` ≤ 255 chars | Schema | Prevents database column overflow |
| At least one stage required | Schema | A pipeline must define at least one stage |
| Stage `name` required | Schema | Every stage must have a name |
| At least one job per stage | Schema | Empty stages are not allowed |
| Job `name` required | Schema | Every job must have a name |
| Job `type` required | Schema | Must specify a job type |
| Job `type` valid enum | Schema | Must be one of: BUILD, TEST, SCAN, DEPLOY, PACKAGE, CUSTOM |
| Stage names unique | Semantic | No two stages can share the same name (case-insensitive) |
| Job names unique per stage | Semantic | No two jobs within a stage can share the same name (case-insensitive) |
| Stage `dependsOn` references exist | Semantic | Each dependency must reference an existing stage name |
| Job `dependsOn` references exist | Semantic | Each dependency must reference an existing job in the same stage |
| No stage dependency cycles | Dependency | Stage dependency graph must be a DAG |
| No job dependency cycles within stages | Dependency | Per-stage job dependency graph must be a DAG |

---

## 5. Architecture Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                        REST API Layer                            │
│                                                                  │
│  POST /api/v1/pipelines/{id}/versions                           │
│  POST /api/v1/pipelines/yaml?projectId=UUID                     │
└─────────────────────────────┬────────────────────────────────────┘
                              │ SubmitPipelineYamlRequest
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│                   PipelineYamlService                            │
│                                                                  │
│  1. Parse YAML → PipelineConfig                                 │
│  2. Validate (schema → semantic → dependency)                   │
│  3. Map to domain entities                                      │
│  4. Persist as PipelineVersion                                  │
└────┬────────────┬──────────────────┬────────────────────────────┘
     │            │                  │
     ▼            ▼                  ▼
┌─────────┐ ┌──────────────┐ ┌─────────────────────┐
│ Parser  │ │  Validators  │ │    Domain Mapper     │
│         │ │              │ │                      │
│SnakeYAML│ │Schema        │ │PipelineConfigMapper  │
│         │ │Semantic      │ │                      │
│         │ │Dependency    │ │→ Pipeline            │
│         │ │              │ │→ StageDefinition[]   │
│         │ │PipelineValid-│ │→ JobDefinition[]     │
│         │ │ationResult   │ │                      │
└─────────┘ └──────────────┘ └─────────────────────┘
     │            │                  │
     ▼            ▼                  ▼
┌──────────────────────────────────────────────────────────────────┐
│                   Pipeline Validation Exception                   │
│                                                                  │
│  PipelineValidationException (422 UNPROCESSABLE_ENTITY)          │
│  → GlobalExceptionHandler → ApiErrorResponse                     │
└──────────────────────────────────────────────────────────────────┘
```

---

## 6. Config Model (YAML → Java POJOs)

The YAML content is deserialized into a hierarchy of plain Java objects:

```
PipelineRoot
└── PipelineConfig pipeline
    ├── String name
    ├── String description
    └── List<StageConfig> stages
        ├── String name
        ├── List<String> dependsOn
        └── List<JobConfig> jobs
            ├── String name
            ├── String type
            └── List<String> dependsOn
```

### PipelineRoot

```java
// pipeline/config/PipelineRoot.java
public class PipelineRoot {
    private PipelineConfig pipeline;
    // getter/setter
}
```

Top-level deserialization target. SnakeYAML maps the `pipeline:` YAML key to this field.

### PipelineConfig

```java
// pipeline/config/PipelineConfig.java
public class PipelineConfig {
    private String name;
    private String description;
    private List<StageConfig> stages = new ArrayList<>();
}
```

Represents the pipeline definition. Stages default to empty list to prevent NPE.

### StageConfig

```java
// pipeline/config/StageConfig.java
public class StageConfig {
    private String name;
    private List<JobConfig> jobs = new ArrayList<>();
    private List<String> dependsOn = new ArrayList<>();
}
```

Represents a pipeline stage with its jobs and inter-stage dependencies.

### JobConfig

```java
// pipeline/config/JobConfig.java
public class JobConfig {
    private String name;
    private String type;          // String, validated against enum in SchemaValidator
    private List<String> dependsOn = new ArrayList<>();
}
```

Represents a job within a stage. The `type` is stored as a String for flexibility and validated against the allowed enum values during schema validation.

---

## 7. Parser Layer

### PipelineYamlParser

**File:** `pipeline/parser/PipelineYamlParser.java`

The parser wraps SnakeYAML 2.x with a type-safe constructor:

```java
public PipelineYamlParser() {
    LoaderOptions options = new LoaderOptions();
    Constructor constructor = new Constructor(PipelineRoot.class, options);
    this.yaml = new Yaml(constructor);
}
```

**Key design decisions:**

| Decision | Rationale |
|----------|-----------|
| `Constructor(PipelineRoot.class, LoaderOptions)` | SnakeYAML 2.x requires explicit type binding for security — prevents arbitrary class instantiation |
| `LoaderOptions` (default) | Uses SnakeYAML default limits for recursion depth and code point count |
| `PipelineYamlParseException` (unchecked) | Parse errors are unrecoverable within the request scope; propagates to exception handler |
| Null/blank check before parsing | Provides a clear error message instead of SnakeYAML's internal error |

**Error handling strategy:**

| Input | Exception | Message |
|-------|-----------|---------|
| `null` | `PipelineYamlParseException` | "Pipeline YAML content is empty" |
| `""` / `"  "` | `PipelineYamlParseException` | "Pipeline YAML content is empty" |
| `"invalid [{"` | `PipelineYamlParseException` | "Invalid YAML syntax: ..." |
| `"stages: [...]"` (no `pipeline` key) | `PipelineYamlParseException` | "YAML must contain a top-level 'pipeline' key" |
| `"pipeline: "` (null value) | `PipelineYamlParseException` | "YAML must contain a top-level 'pipeline' key" |

---

## 8. Validation Architecture

Module 2 implements a **three-level validation pipeline** with fail-fast semantics:

```
YAML Content
    │
    ▼
┌─────────────┐
│   Schema     │ ──── If fails → return errors immediately
│  Validator   │
└──────┬──────┘
       │ (only if schema passes)
       ▼
┌─────────────┐
│  Semantic    │ ──── If fails → return errors immediately
│  Validator   │
└──────┬──────┘
       │ (only if semantic passes)
       ▼
┌─────────────┐
│ Dependency   │ ──── Return accumulated errors
│  Validator   │
└─────────────┘
```

### Why three levels?

| Level | Purpose | Example |
|-------|---------|---------|
| **Schema** | Structural correctness — are all required fields present and well-typed? | Missing `pipeline.name`, invalid job `type` |
| **Semantic** | Meaning correctness — do references resolve and names follow uniqueness rules? | Duplicate stage names, job depends on non-existent job |
| **Dependency** | Graph correctness — is the dependency graph acyclic? | Circular stage dependencies, circular job dependencies |

Schema validation runs first because semantic and dependency validation assume the structure is valid. If the schema is invalid, semantic checks would NPE on null fields. Dependency validation runs last because it requires a complete, structurally valid graph to traverse.

### PipelineValidationResult

```java
// pipeline/validator/PipelineValidationResult.java
public class PipelineValidationResult {
    private final List<PipelineValidationFieldError> errors;

    public void addError(String path, String code, String message) { ... }
    public boolean isValid() { return errors.isEmpty(); }
    public List<PipelineValidationFieldError> getErrors() { ... }
}
```

### PipelineValidationFieldError

```java
// pipeline/validator/PipelineValidationFieldError.java
public record PipelineValidationFieldError(
    String path,    // e.g., "pipeline.stages[0].jobs[1].type"
    String code,    // e.g., "REQUIRED", "INVALID", "DUPLICATE", "CYCLIC_DEPENDENCY"
    String message  // Human-readable description
) {}
```

**Error path convention:** Uses JSONPointer-style paths:
- `pipeline.name` — top-level field
- `pipeline.stages[0].name` — indexed stage field
- `pipeline.stages[0].jobs[1].type` — indexed job field within stage
- `pipeline.stages[0].dependsOn` — stage dependency list
- `pipeline.stages[0].jobs[1].dependsOn` — job dependency list

---

## 9. Schema Validator

**File:** `pipeline/validator/SchemaValidator.java`

Validates the structural correctness of the parsed YAML configuration.

### Validation Rules

| Rule | Path | Code |
|------|------|------|
| Pipeline name is non-null and non-blank | `pipeline.name` | `REQUIRED` |
| Pipeline name ≤ 255 characters | `pipeline.name` | `SIZE` |
| Pipeline has at least one stage | `pipeline.stages` | `REQUIRED` |
| Each stage has a non-null/non-blank name | `pipeline.stages[i].name` | `REQUIRED` |
| Stage name ≤ 255 characters | `pipeline.stages[i].name` | `SIZE` |
| Each stage has at least one job | `pipeline.stages[i].jobs` | `REQUIRED` |
| Each job has a non-null/non-blank name | `pipeline.stages[i].jobs[j].name` | `REQUIRED` |
| Job name ≤ 255 characters | `pipeline.stages[i].jobs[j].name` | `SIZE` |
| Job type is non-null/non-blank | `pipeline.stages[i].jobs[j].type` | `REQUIRED` |
| Job type is one of BUILD/TEST/SCAN/DEPLOY/PACKAGE/CUSTOM | `pipeline.stages[i].jobs[j].type` | `INVALID` |

### Valid Job Types

```java
private static final Set<String> VALID_JOB_TYPES = Set.of(
    "BUILD", "TEST", "SCAN", "DEPLOY", "PACKAGE", "CUSTOM"
);
```

---

## 10. Semantic Validator

**File:** `pipeline/validator/SemanticValidator.java`

Validates the meaning and consistency of the parsed YAML configuration.

### Validation Rules

| Rule | Path | Code |
|------|------|------|
| Stage names are unique (case-insensitive) | `pipeline.stages[i].name` | `DUPLICATE` |
| Job names are unique within each stage (case-insensitive) | `pipeline.stages[i].jobs[j].name` | `DUPLICATE` |
| Stage `dependsOn` references existing stage names | `pipeline.stages[i].dependsOn` | `INVALID_REFERENCE` |
| Job `dependsOn` references existing job names within same stage | `pipeline.stages[i].jobs[j].dependsOn` | `INVALID_REFERENCE` |

### Case-Insensitive Uniqueness

Stage and job name uniqueness is enforced case-insensitively by comparing `.toLowerCase()` values. This prevents confusion between "Build" and "build" as stage names.

---

## 11. Dependency Validator

**File:** `pipeline/validator/DependencyValidator.java`

Detects circular dependencies using depth-first search (DFS).

### Algorithm

The validator builds a directed graph from the `dependsOn` relationships and runs DFS with three states:

| State | Meaning |
|-------|---------|
| Unvisited | Node not yet explored |
| Visiting | Node is on the current DFS path (ancestor) |
| Visited | Node and all descendants fully explored |

If DFS encounters a "Visiting" node, a cycle exists.

```
dfs(node):
  if node in visiting → CYCLE DETECTED
  if node in visited → return (already safe)
  visiting.add(node)
  for neighbor in node.dependsOn:
    if dfs(neighbor) → return true
  visiting.remove(node)
  visited.add(node)
  return false
```

### Scope of Cycle Detection

| Level | Graph Scope | Error Path |
|-------|-------------|------------|
| Stage cycles | All stages form one graph | `pipeline.stages` |
| Job cycles | Jobs within each stage form separate graphs | `pipeline.stages.jobs` |

---

## 12. Domain Mapper

**File:** `pipeline/PipelineConfigMapper.java`

Maps the validated YAML configuration to domain entities for persistence.

### Mapping Methods

| Method | Input | Output | Description |
|--------|-------|--------|-------------|
| `toPipeline()` | `PipelineConfig`, `Project` | `Pipeline` | Creates a new `Pipeline` entity with name and description |
| `toStageDefinitions()` | `PipelineConfig` | `List<StageDefinition>` | Extracts ordered stage definitions with job type resolution |
| `resolveJobType()` | `String type` | `PipelineJob.JobType` | Maps string type to enum, defaulting to CUSTOM for unknown values |

### Internal Records

```java
public record StageDefinition(String name, int orderIndex, List<JobDefinition> jobs) {}
public record JobDefinition(String name, PipelineJob.JobType jobType) {}
```

These records are used for validation and future execution mapping (Module 3), not for direct persistence. Module 2 only persists the YAML content in `PipelineVersion.yamlContent`.

---

## 13. Service Layer

**File:** `pipeline/PipelineYamlService.java`

Orchestrates the full YAML submission workflow.

### submitYaml(UUID pipelineId, String yamlContent, String createdBy)

```
1. Load Pipeline from database (or throw 404)
2. Parse YAML → PipelineConfig
3. Validate (schema → semantic → dependency)
4. If errors → throw PipelineValidationException
5. Determine next version number (max existing + 1)
6. Create and persist PipelineVersion
7. Return saved version
```

### validateAndSubmitToProject(UUID projectId, String yamlContent, String createdBy)

```
1. Load Project from database (or throw 404)
2. Parse YAML → PipelineConfig
3. Validate (schema → semantic → dependency)
4. If errors → throw PipelineValidationException
5. Find or create Pipeline within project (by name matching)
6. Determine next version number
7. Create and persist PipelineVersion
8. Return saved version
```

### Pipeline Validation Exception Translation

Parse errors from `PipelineYamlParseException` are caught and translated into `PipelineValidationException` with a `PARSE_ERROR` code, ensuring a consistent error response format for all validation failures.

---

## 14. REST API Endpoints

### POST /api/v1/pipelines/{id}/versions

Submit YAML content to an existing pipeline, creating a new version.

**Request:**
```http
POST /api/v1/pipelines/550e8400-e29b-41d4-a716-446655440000/versions
Content-Type: application/json

{
  "yamlContent": "pipeline:\n  name: ci\n  stages:\n    - name: build\n      jobs:\n        - name: compile\n          type: BUILD\n"
}
```

**Success Response (201):**
```json
{
  "id": "660e8400-e29b-41d4-a716-446655440001",
  "pipelineId": "550e8400-e29b-41d4-a716-446655440000",
  "version": 1,
  "commitSha": null,
  "createdBy": null,
  "createdAt": "2026-08-19T00:00:00Z"
}
```

**Error Responses:**

| Status | Code | Condition |
|--------|------|-----------|
| 404 | `RESOURCE_NOT_FOUND` | Pipeline ID does not exist |
| 422 | `PIPELINE_VALIDATION_ERROR` | YAML fails schema/semantic/dependency validation |
| 400 | `VALIDATION_FAILED` | Request body missing `yamlContent` |

### POST /api/v1/pipelines/yaml?projectId=UUID

Submit YAML content to a project, auto-creating the pipeline if it doesn't exist.

**Request:**
```http
POST /api/v1/pipelines/yaml?projectId=770e8400-e29b-41d4-a716-446655440002
Content-Type: application/json

{
  "yamlContent": "pipeline:\n  name: my-pipeline\n  ..."
}
```

**Behavior:**
- If a pipeline named `my-pipeline` already exists in the project, a new version is created for it
- If no pipeline with that name exists, a new `Pipeline` is created and version 1 is assigned

---

## 15. DTOs

### SubmitPipelineYamlRequest

```java
public record SubmitPipelineYamlRequest(
    @NotBlank(message = "YAML content is required")
    String yamlContent
) {}
```

### PipelineValidationError

```java
public record PipelineValidationError(
    String path,
    String code,
    String message
) {
    public static PipelineValidationError from(PipelineValidationFieldError error) {
        return new PipelineValidationError(error.path(), error.code(), error.message());
    }
}
```

---

## 16. Exception Handling

### GlobalExceptionHandler Integration

The existing `GlobalExceptionHandler` was extended with a new handler:

```java
@ExceptionHandler(PipelineValidationException.class)
public ResponseEntity<ApiErrorResponse> handlePipelineValidation(PipelineValidationException ex) {
    // Maps validation errors to details map
    // Returns 422 UNPROCESSABLE_ENTITY
}
```

**Error Response Format (422):**
```json
{
  "code": "PIPELINE_VALIDATION_ERROR",
  "message": "Pipeline configuration is invalid",
  "details": {
    "pipeline.name": "REQUIRED: Pipeline name is required",
    "pipeline.stages[0].jobs[0].type": "INVALID: Invalid job type 'X'. Valid types: [...]"
  }
}
```

---

## 17. End-to-End Request Flow

```
Client sends POST /api/v1/pipelines/{id}/versions
    │
    ▼
PipelineController.submitYaml()
    │
    ▼
PipelineYamlService.submitYaml()
    │
    ├──→ PipelineRepository.findById() → 404 if not found
    │
    ├──→ PipelineYamlParser.parse()
    │       └── PipelineYamlParseException → 422
    │
    ├──→ SchemaValidator.validate()
    │       └── errors? → PipelineValidationException → 422
    │
    ├──→ SemanticValidator.validate()
    │       └── errors? → PipelineValidationException → 422
    │
    ├──→ DependencyValidator.validate()
    │       └── errors? → PipelineValidationException → 422
    │
    ├──→ PipelineVersionRepository.findByPipelineIdOrderByVersionDesc()
    │
    ├──→ new PipelineVersion(pipeline, maxVersion + 1, yamlContent, ...)
    │
    └──→ PipelineVersionRepository.save() → return
    │
    ▼
PipelineVersionResponse.from(version) → 201 CREATED
```

---

## 18. Testing Architecture

### Test Inventory

| Test Class | Type | Tests | Package |
|------------|------|-------|---------|
| `PipelineYamlParserTest` | Unit | 6 | `pipeline.parser` |
| `SchemaValidatorTest` | Unit | 10 | `pipeline.validator` |
| `SemanticValidatorTest` | Unit | 8 | `pipeline.validator` |
| `DependencyValidatorTest` | Unit | 6 | `pipeline.validator` |
| `PipelineConfigMapperTest` | Unit | 7 | `pipeline` |
| `PipelineYamlServiceTest` | Unit (Mockito) | 6 | `pipeline` |
| `PipelineYamlApiTest` | Integration (MockMvc) | 9 | `api.controller` |

**Total Module 2 tests: 52**

### Unit Tests (No Spring Context)

These tests instantiate classes directly — no Spring context, no database, no HTTP layer.

**PipelineYamlParserTest:**
- Valid YAML parsing with full field verification
- Null/blank/empty YAML rejection
- Missing `pipeline` key detection
- Invalid YAML syntax handling
- Empty `pipeline:` key handling

**SchemaValidatorTest:**
- Valid config passes
- Missing/blank/oversized pipeline name
- Empty stages, missing stage name, empty jobs
- Missing job name, invalid/valid job types

**SemanticValidatorTest:**
- Unique names pass, duplicate stage/job names fail
- Same job name in different stages passes
- Valid/invalid stage and job dependency references

**DependencyValidatorTest:**
- No dependencies pass, linear dependencies pass
- Stage cycles (2-way, 3-way) detected
- Job cycles within stages detected

**PipelineConfigMapperTest:**
- Field mapping (name, description, project)
- Order index mapping for stages
- Job type resolution (valid, null, unknown → CUSTOM)
- Null stages handling

### Service Tests (Mockito)

**PipelineYamlServiceTest:**
- Version creation (v1) with repository mocking
- Version increment (existing v3 → new v4)
- Pipeline not found → `ResourceNotFoundException`
- Invalid YAML → `PipelineValidationException`
- Auto-create pipeline within project
- Reuse existing pipeline by name

### Integration Tests (MockMvc + H2)

**PipelineYamlApiTest:**
- 201: Valid YAML to existing pipeline
- 404: Non-existent pipeline ID
- 422: Invalid YAML syntax
- 422: Missing `pipeline` key
- 201: Valid YAML to project (auto-create pipeline)
- 404: Non-existent project ID
- 201: Duplicate name creates version 2
- 422: Schema errors (missing job type)
- 422: Dependency cycles

---

## 19. Configuration

### Test Profile (application-test.yml)

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: true
  datasource:
    url: jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false
    username: sa
    password:
    driver-class-name: org.h2.Driver
  flyway:
    enabled: false
```

Module 2 requires no additional configuration beyond what Module 1 provides. SnakeYAML is available transitively through `spring-boot-starter-web`.

---

## 20. Build and Verification

```bash
# Compile
mvn compile -q

# Run all tests (97 total: 45 Module 1 + 52 Module 2)
mvn clean test

# Package
mvn package -DskipTests
```

**Build artifact:** `target/cicd-control-plane-0.1.0.jar` (~53MB)

---

## 21. Module Dependencies

```
Module 2 (Pipeline YAML Config)
    │
    ├── depends on ──→ Module 1 (PostgreSQL Domain Model)
    │                    ├── Pipeline entity
    │                    ├── PipelineVersion entity
    │                    ├── PipelineJob.JobType enum
    │                    ├── PipelineRepository
    │                    ├── PipelineVersionRepository
    │                    ├── ProjectRepository
    │                    └── GlobalExceptionHandler
    │
    └── enables ──→ Module 3 (Pipeline Execution Engine)
                      ├── Pipeline stage instantiation
                      ├── Job execution scheduling
                      └── Runtime state management
```

---

## 22. What Module 2 Enables

Module 2 establishes the pipeline configuration interface that Module 3 will use to:

1. **Instantiate runtime entities:** Module 3 will read `StageDefinition` and `JobDefinition` from the mapper to create `PipelineStage` and `PipelineJob` entities during pipeline execution
2. **Track configuration versions:** Each `PipelineVersion` records the exact YAML that was used for a given execution, enabling audit and rollback
3. **Validate before execution:** The 3-level validation pipeline ensures that only correct pipeline definitions can be submitted, preventing runtime failures from malformed configurations
4. **Support CI/CD triggers:** Webhook handlers (Module 4+) can submit YAML configurations to create pipeline versions automatically

---

## 23. Next Module

**Module 3 — Pipeline Execution Engine** will implement:
- Pipeline run instantiation from YAML definitions
- Stage and job execution state management
- Worker dispatch and task coordination
- Execution lifecycle tracking (PENDING → RUNNING → SUCCESS/FAILED)
