# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

BX-bot is a 12-module Java 21 / Spring Boot 3.3.5 cryptocurrency trading bot. It builds with **both**
Maven and Gradle — both are kept in sync and both run in CI.

## Build & test commands

Use the wrappers: `./mvnw` (Maven 3.9.9) and `./gradlew` (Gradle 8.11). Maven's `<defaultGoal>` is `clean install`.

| Task | Maven | Gradle |
|---|---|---|
| Build + unit tests | `./mvnw clean install` | `./gradlew build` |
| Integration tests only | `./mvnw clean install -Pint` | `./gradlew integrationTests` |
| Unit + integration | `./mvnw clean install -Pall` | `./gradlew build integrationTests` |
| Single module | `./mvnw clean install -pl bxbot-core -am` | `./gradlew :bxbot-core:build` |
| Single test class | `./mvnw test -pl bxbot-exchanges -Dtest=TestBitstampExchangeAdapter` | `./gradlew :bxbot-exchanges:test --tests '*TestBitstampExchangeAdapter'` |
| Single test method | `./mvnw test -pl bxbot-core -Dtest='TestTradingEngine#testEngineInitialisesSuccessfully'` | `./gradlew :bxbot-core:test --tests '*TestTradingEngine.testEngineInitialisesSuccessfully'` |
| Single integration test | `./mvnw verify -Pint -pl bxbot-exchanges -Dit.test=BitstampIT` | `./gradlew :bxbot-exchanges:integrationTests --tests '*BitstampIT'` |
| Checkstyle | `./mvnw checkstyle:check` | `./gradlew checkstyleMain checkstyleTest` |
| SpotBugs | `./mvnw spotbugs:check` | `./gradlew spotbugsMain` |
| Coverage report | `./mvnw jacoco:report` | `./gradlew jacocoTestReport` |
| Javadoc | `./mvnw javadoc:javadoc` | `./gradlew javadoc` |
| Distribution | `./mvnw clean package` → `bxbot-app/target/*-dist.{tar.gz,zip}` | `./gradlew build buildTarGzipDist` |

- **JDK 21 is required** (enforcer `requireJavaVersion 21`; both CI workflows use JDK 21).
- Always scope single-test runs with `-pl <module>`. If you add `-am` (or run reactor-wide), also pass
  `-Dsurefire.failIfNoSpecifiedTests=false`, or the upstream modules with no matching test fail the build.
- Maven profiles (root `pom.xml`): `unit` (active by default), `int`, `all`, plus a non-default `spotbugs` profile.
- Static-analysis config lives in `etc/`: `google_checks.xml`, `checkstyle-suppressions.xml`,
  `spotbugs-exclude-filter.xml`. PMD is not configured; google-java-format is an IDE recommendation only,
  not a build plugin.
- **Checkstyle and JaCoCo fail the build**, not just failing tests: Checkstyle runs Google style with
  `violationSeverity = warning` (Gradle: `maxWarnings = 0`), and JaCoCo enforces **0.8 LINE COVEREDRATIO at
  CLASS level**. PowerMock-instrumented `com.gazbert.bxbot.exchanges.*Adapter` classes are excluded from coverage.
- **These plugins are declared per module, and the coverage is not uniform** — the root `pom.xml` only
  configures them in `<pluginManagement>`, so a module opts in by declaring the plugin itself:
  - Checkstyle: every module **except `bxbot-core`** (and the source-less `bxbot-app`). Changes to `bxbot-core`
    are therefore *not* style-checked by a normal build or by CI — run `./mvnw checkstyle:check -pl bxbot-core`
    by hand. It currently reports 3 pre-existing `MissingJavadocMethod` warnings in test fixtures.
  - JaCoCo: every module except `bxbot-exchange-api` and `bxbot-app`.
- Maven's `<configLocation>google_checks.xml</configLocation>` has no `etc/` prefix and no such file exists at
  the repo root, so Maven uses the copy bundled inside the checkstyle jar; only Gradle reads `etc/google_checks.xml`
  and `etc/checkstyle-suppressions.xml`. The two builds can apply slightly different rules.
- CI: `.github/workflows/maven.yml` (`mvn -T 1C -B verify ... -Pall`) and `gradle.yml`
  (`./gradlew build integrationTests jacocoTestReport sonarqube`). Both run integration tests and SonarCloud.

## Testing conventions

- **Unit test classes must be named `TestFoo.java`, not `FooTest.java`.** Surefire is configured with
  `<include>**/Test*.java</include>` and `<exclude>**/*IT.java</exclude>`, so a conventionally-suffixed test
  class is silently never run. Examples: `TestTradingEngine`, `TestBitstampExchangeAdapter`.
- Integration tests live in `bxbot-exchanges/src/integration-test/java` and are named `*IT.java`
  (`BitstampIT`, `KrakenIT`, `BitfinexIT`, `GeminiIT`). They are wired via `build-helper-maven-plugin` (Maven)
  and a custom `integrationTests` source set + task (Gradle). **They call live exchange APIs.**
- Mocking is **EasyMock 5.4.0**, not Mockito. Mockito appears only through Spring Boot's `@MockBean` in
  `bxbot-rest-api`.
- **PowerMock 2.0.9** is used in exactly five exchange-adapter tests. PowerMock has no JUnit 5 support, so
  those stay on JUnit 4 and `junit-vintage-engine` is pulled in for them. This is why `bxbot-exchanges`
  carries a long `--add-opens` JVM-arg list in both build files — don't remove it.
- No module declares `useJUnitPlatform()` in Gradle. When test results differ between the two builds,
  treat Maven as the reference.

## Architecture

Two independent stacks that meet at `bxbot-core`:

```
bxbot-trading-api                       (pure SPI — no Spring, no bxbot deps)
  ├─ bxbot-exchange-api  → bxbot-exchanges
  └─ bxbot-strategy-api  → bxbot-strategies

bxbot-domain-objects → bxbot-yaml-datastore → bxbot-repository → bxbot-services
                                                                    ├─ bxbot-core
                                                                    └─ bxbot-rest-api
bxbot-app → everything                  (assembly only; no Java sources)
```

- **`bxbot-trading-api`** — `TradingApi` plus value types (`Market`, `Ticker`, `OpenOrder`, `MarketOrderBook`,
  `BalanceInfo`, `OrderType`) and `TradingApiException` / `ExchangeNetworkException`.
- **`bxbot-exchange-api`** — `ExchangeAdapter extends TradingApi`, adding `init(ExchangeConfig)`; plus
  `AuthenticationConfig` / `NetworkConfig` / `OtherConfig` / `PairPrecisionConfig`.
- **`bxbot-strategy-api`** — `TradingStrategy` (`init(TradingApi, Market, StrategyConfig)` + `execute()`),
  `StrategyConfig`, `StrategyException`.
- **`bxbot-exchanges`** — Bitstamp / Bitfinex / Kraken / Gemini adapters plus `TryModeExchangeAdapter`
  (paper-trading decorator: reads `otherConfig.delegateAdapter`, reflectively loads a real adapter for public
  calls, simulates order management). All extend `AbstractExchangeAdapter`, which holds the HTTP/auth plumbing.
- **`bxbot-strategies`** — `ExampleScalpingStrategy`, annotated `@Component("exampleScalpingStrategy")` — the
  bean name the shipped `config/strategies.yaml` references.
- **`bxbot-domain-objects`** — Lombok `@Data` config POJOs under
  `com.gazbert.bxbot.domain.{engine,exchange,market,strategy,emailalerts,bot}`.
- **`bxbot-yaml-datastore`** — `ConfigurationManager` (synchronized SnakeYAML load/save) and `FileLocations`,
  which hardcodes `config/{engine,exchange,markets,strategies,email-alerts}.yaml` relative to the CWD.
- **`bxbot-repository`** — repository interfaces + `yaml/` implementations. Every call re-reads the YAML file.
- **`bxbot-services`** — `services/config/*` (CRUD over repositories) and `services/runtime/*`, which wrap
  Actuator health/logfile and the Spring Cloud `RestartEndpoint`.
- **`bxbot-core`** — `BxBot`, `TradingEngine`, config builders, `EmailAlerter`.
- **`bxbot-rest-api`** — `v1/config/*Controller`, `v1/runtime/*Controller`, JWT/RBAC security, H2 users seeded
  from `import.sql`.
- **`bxbot-app`** — no Java sources. Assembles the Spring Boot fat jar and tar.gz/zip via
  `assembly/distribution.xml`. This is where third-party strategy/exchange jars get added to the runtime classpath.

**The Spring Boot `main` class is in `bxbot-core`, not `bxbot-app`** —
`bxbot-core/src/main/java/com/gazbert/bxbot/BxBot.java`.

### Config vertical slice

The five config types (engine, exchange, markets, strategies, email-alerts) each repeat the same five-layer
chain, so a change to one is usually a change to all five:

```
config/*.yaml → ConfigurationManager → XxxConfigYamlRepository → XxxConfigService → XxxConfigController
```

The `@Qualifier("...YamlRepository")` in each service impl is the single swap point for a non-YAML datastore.

Endpoints (`rest/api/v1/EndpointLocations.java`): `/api/v1/config/{strategies,markets,engine,exchange,email-alerts}`
(GET requires `ROLE_USER`, mutations require `ROLE_ADMIN`), `/api/v1/runtime/{status,restart,logfile,logfile/download}`,
and `POST /api/token` for JWT.

### Runtime flow

1. `BxBot.main()` — `@SpringBootApplication implements CommandLineRunner`; `run()` calls `tradingEngine.start()`
   and blocks the main thread for the life of the process.
2. `TradingEngine.init()` — order matters: `loadExchangeAdapter()` → `loadEngineConfig()` → `loadTradingStrategies()`.
3. `runMainControlLoop()` (`bxbot-core/src/main/java/com/gazbert/bxbot/core/engine/TradingEngine.java:165`) —
   `while (keepAlive)`: emergency-stop balance check → `tradingStrategy.execute()` for each strategy →
   sleep `tradeCycleInterval` seconds.
4. **Fail hard and fast.** `StrategyException`, `TradingApiException`, and any unexpected `Exception` log FATAL,
   send an email alert, and set `keepAlive = false`. Only `ExchangeNetworkException` is recoverable — it logs
   and sleeps until the next cycle.
5. Single-threaded by design — one thread passes through strategies and adapters, so extension code needs no
   concurrency handling.

### Extension points

- `core/util/ConfigurableComponentFactory` does plain reflection
  (`Class.forName(...).getDeclaredConstructor().newInstance()`), so exchange adapters and `className`-configured
  strategies need a **public no-arg constructor** and must be on the runtime classpath.
- `core/config/strategy/TradingStrategyFactory` — if `strategies.yaml` sets `beanName`, the strategy is resolved
  via `ApplicationContext.getBean(...)` (use this when the strategy itself needs `@Autowired` dependencies);
  otherwise it falls back to `className` reflection. The two are mutually exclusive in config.
- `core/config/strategy/TradingStrategiesBuilder` walks `markets.yaml`, skips `enabled: false`, rejects duplicate
  markets, matches `tradingStrategyId` against the strategy map, then calls
  `strategy.init(exchangeAdapter, market, config)` — **passing the exchange adapter as the `TradingApi`**.
  That single call is the whole join between the two SPI stacks.
- `exchange.yaml` `adapter:` is a fully-qualified class name; `ExchangeApiConfigBuilder` maps the domain config
  onto the exchange-api impls before calling `adapter.init(...)`. One exchange per bot, by design.
- Config changes require a restart: `POST /api/v1/runtime/bot/restart`, or `./bxbot.sh stop && ./bxbot.sh start`.

## Running the bot

`./bxbot.sh start|stop|status` (Windows: `bxbot.bat`) runs from the **unpacked distribution** — jar in `./libs`,
config in `./config`, PID in `./.bxbot.pid` — and the jar filename is hardcoded per release. The `Dockerfile`
builds on `eclipse-temurin:21-jdk` and has no `CMD`: exec into the container, `cd bxbot*`, then `./bxbot.sh start`.

## Conventions

- Root package is `com.gazbert.bxbot.*`, but package names do **not** mirror module names uniformly:

  | Module | Package |
  |---|---|
  | `bxbot-core` | `com.gazbert.bxbot` (`BxBot` only) + `com.gazbert.bxbot.core.{engine,config.*,mail,util}` |
  | `bxbot-yaml-datastore` | `com.gazbert.bxbot.datastore.yaml.*` |
  | `bxbot-repository` | `com.gazbert.bxbot.repository` (interfaces) + `.repository.yaml` (impls) |
  | `bxbot-services` | `com.gazbert.bxbot.services.{config,runtime}` + `.impl` |
  | `bxbot-rest-api` | `com.gazbert.bxbot.rest.api.{v1.config,v1.runtime,security.*}` |
  | `bxbot-{trading,exchange,strategy}-api` | `com.gazbert.bxbot.{trading,exchange,strategy}.api` |

- Interface + `Impl` / `YamlRepository` suffix, with an explicit Spring bean name in the annotation.
- Lombok `@Log4j2` for logging (the field is `log`); `@Data` on domain objects.
- Every `.java` file opens with the ~22-line MIT license header — copy it from an existing file. New files need
  a class Javadoc with an `@author` tag; public API methods need full Javadoc.
- Commit messages use Conventional Commits with an issue reference, e.g. `fix: Fixed the NullPointer bug (#123)`.
  Keep commits atomic; don't mix formatting fixes or code moves with real changes.

## This checkout

A fork — `origin` is `git@github.com:luca-valensisi/bxbot.git`. Work happens on `develop`; the upstream default
branch is `master`.
